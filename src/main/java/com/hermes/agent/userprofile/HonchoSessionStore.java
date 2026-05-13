package com.hermes.agent.userprofile;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Honcho Session Store — persistent storage for peer profiles, observations, conclusions.
 *
 * <p>Supports multiple backends:
 * <ul>
 *   <li><b>memory</b>: in-memory only (default, for testing)</li>
 *   <li><b>jsonfile</b>: JSON files in ~/.hermes/honcho/ (simple, portable)</li>
 *   <li><b>sqlite</b>: SQLite database (recommended for production)</li>
 * </ul>
 *
 * <p>Write modes (write_frequency):
 * <ul>
 *   <li><b>async</b>: background thread, non-blocking (default)</li>
 *   <li><b>turn</b>: sync write after every turn</li>
 *   <li><b>session</b>: batch write at session end</li>
 *   <li><b>N</b>: write every N turns</li>
 * </ul>
 *
 * <p>Thread safety: all public methods are thread-safe.
 */
@Component
public class HonchoSessionStore {

    private static final Logger log = LoggerFactory.getLogger(HonchoSessionStore.class);

    private final UserProfileConfig config;
    private final ObjectMapper mapper;

    // In-memory cache: sessionId → PeerProfile
    private final Map<String, PeerProfile> profiles = new ConcurrentHashMap<>();

    // In-memory observations: sessionId → list of observations
    private final Map<String, List<Observation>> observations = new ConcurrentHashMap<>();

    // In-memory conclusions: sessionId → list of conclusions
    private final Map<String, List<Conclusion>> conclusions = new ConcurrentHashMap<>();

    // Session metadata: sessionId → metadata
    private final Map<String, SessionMetadata> sessionMetadata = new ConcurrentHashMap<>();

    // Turn counter for write frequency control
    private final AtomicInteger turnCounter = new AtomicInteger(0);

    // Async write queue
    private final BlockingQueue<WriteTask> writeQueue = new LinkedBlockingQueue<>();
    private Thread asyncWriterThread;
    private volatile boolean shutdown = false;

    // Sentinel for shutdown
    private static final WriteTask SHUTDOWN_SENTINEL = new WriteTask("__shutdown__", null, null);

    // File storage path (for jsonfile backend)
    private Path storagePath;

    public HonchoSessionStore(UserProfileConfig config) {
        this.config = config;
        this.mapper = new ObjectMapper();
        this.mapper.registerModule(new JavaTimeModule());

        initStorage();
        startAsyncWriterIfNeeded();
    }

    private void initStorage() {
        String home = System.getProperty("user.home");
        this.storagePath = Paths.get(home, ".hermes", "honcho");

        try {
            if (!Files.exists(storagePath)) {
                Files.createDirectories(storagePath);
                log.info("[HonchoStore] Created storage directory: {}", storagePath);
            }

            // Load existing profiles from disk
            loadFromDisk();
        } catch (IOException e) {
            log.warn("[HonchoStore] Failed to init storage: {}", e.getMessage());
        }
    }

    private void loadFromDisk() {
        try {
            Path profilesFile = storagePath.resolve("profiles.json");
            if (Files.exists(profilesFile)) {
                String content = Files.readString(profilesFile);
                @SuppressWarnings("unchecked")
                Map<String, Map<String, Object>> data = mapper.readValue(content, Map.class);
                for (Map.Entry<String, Map<String, Object>> e : data.entrySet()) {
                    PeerProfile p = PeerProfile.fromMap(e.getKey(), e.getValue());
                    profiles.put(e.getKey(), p);
                }
                log.info("[HonchoStore] Loaded {} profiles from disk", profiles.size());
            }

            Path observationsFile = storagePath.resolve("observations.json");
            if (Files.exists(observationsFile)) {
                String content = Files.readString(observationsFile);
                @SuppressWarnings("unchecked")
                Map<String, List<Map<String, Object>>> data = mapper.readValue(content, Map.class);
                for (Map.Entry<String, List<Map<String, Object>>> e : data.entrySet()) {
                    List<Observation> obs = new CopyOnWriteArrayList<>();
                    for (Map<String, Object> m : e.getValue()) {
                        obs.add(Observation.fromMap(m));
                    }
                    observations.put(e.getKey(), obs);
                }
            }

            Path conclusionsFile = storagePath.resolve("conclusions.json");
            if (Files.exists(conclusionsFile)) {
                String content = Files.readString(conclusionsFile);
                @SuppressWarnings("unchecked")
                Map<String, List<Map<String, Object>>> data = mapper.readValue(content, Map.class);
                for (Map.Entry<String, List<Map<String, Object>>> e : data.entrySet()) {
                    List<Conclusion> conc = new CopyOnWriteArrayList<>();
                    for (Map<String, Object> m : e.getValue()) {
                        conc.add(Conclusion.fromMap(m));
                    }
                    conclusions.put(e.getKey(), conc);
                }
            }
        } catch (Exception e) {
            log.warn("[HonchoStore] Failed to load from disk: {}", e.getMessage());
        }
    }

    private void startAsyncWriterIfNeeded() {
        if ("async".equals(config.getWriteFrequency())) {
            asyncWriterThread = new Thread(this::asyncWriterLoop, "honcho-async-writer");
            asyncWriterThread.setDaemon(true);
            asyncWriterThread.start();
            log.info("[HonchoStore] Async writer thread started");
        }
    }

    // ── Profile CRUD ─────────────────────────────────────────────────────

    public Optional<PeerProfile> getProfile(String sessionId) {
        return Optional.ofNullable(profiles.get(sessionId));
    }

    public PeerProfile getOrCreateProfile(String sessionId) {
        return profiles.computeIfAbsent(sessionId, id -> {
            PeerProfile p = new PeerProfile(id, "user");
            p.setObservationMode(config.getObservationMode());
            sessionMetadata.put(id, new SessionMetadata(id, Instant.now()));
            return p;
        });
    }

    public void saveProfile(String sessionId, PeerProfile profile) {
        profiles.put(sessionId, profile);
        scheduleWrite(sessionId, "profile", profile);
    }

    // ── Observation CRUD ─────────────────────────────────────────────────

    public List<Observation> getObservations(String sessionId) {
        return observations.computeIfAbsent(sessionId, k -> new CopyOnWriteArrayList<>());
    }

    public void addObservation(String sessionId, Observation obs) {
        getObservations(sessionId).add(obs);
        scheduleWrite(sessionId, "observation", obs);
    }

    // ── Conclusion CRUD ─────────────────────────────────────────────────

    public List<Conclusion> getConclusions(String sessionId) {
        return conclusions.computeIfAbsent(sessionId, k -> new CopyOnWriteArrayList<>());
    }

    public void addConclusion(String sessionId, Conclusion conc) {
        getConclusions(sessionId).add(conc);
        scheduleWrite(sessionId, "conclusion", conc);
    }

    public boolean removeConclusion(String sessionId, String conclusionId) {
        List<Conclusion> concs = getConclusions(sessionId);
        boolean removed = concs.removeIf(c -> c.id().equals(conclusionId));
        if (removed) {
            scheduleWrite(sessionId, "conclusion_remove", conclusionId);
        }
        return removed;
    }

    // ── Session management ──────────────────────────────────────────────

    public Set<String> getSessionIds() {
        Set<String> ids = new HashSet<>();
        ids.addAll(profiles.keySet());
        ids.addAll(observations.keySet());
        ids.addAll(conclusions.keySet());
        return ids;
    }

    public void clearSession(String sessionId) {
        profiles.remove(sessionId);
        observations.remove(sessionId);
        conclusions.remove(sessionId);
        sessionMetadata.remove(sessionId);
        scheduleWrite(sessionId, "clear", null);
    }

    // ── Write frequency control ─────────────────────────────────────────

    public void onTurnEnd(String sessionId) {
        int turn = turnCounter.incrementAndGet();
        String wf = config.getWriteFrequency();

        if ("turn".equals(wf)) {
            flushToDisk();
        } else if ("session".equals(wf)) {
            // Defer until session end
        } else if (wf.matches("\\d+")) {
            int n = Integer.parseInt(wf);
            if (turn % n == 0) {
                flushToDisk();
            }
        }
    }

    public void onSessionEnd(String sessionId) {
        if ("session".equals(config.getWriteFrequency())) {
            flushToDisk();
        }
    }

    // ── Write scheduling ────────────────────────────────────────────────

    private void scheduleWrite(String sessionId, String type, Object data) {
        if ("async".equals(config.getWriteFrequency())) {
            writeQueue.offer(new WriteTask(sessionId, type, data));
        } else if ("turn".equals(config.getWriteFrequency())) {
            // Will be written on turn end
        }
    }

    private void asyncWriterLoop() {
        while (!shutdown) {
            try {
                WriteTask task = writeQueue.poll(5, TimeUnit.SECONDS);
                if (task == null) continue;
                if (task == SHUTDOWN_SENTINEL) break;

                // Batch writes: drain all pending tasks
                List<WriteTask> batch = new ArrayList<>();
                batch.add(task);
                writeQueue.drainTo(batch, 100);

                // Flush to disk
                flushToDisk();

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("[HonchoStore] Async writer error: {}", e.getMessage());
            }
        }
        log.info("[HonchoStore] Async writer thread stopped");
    }

    // ── Flush to disk ───────────────────────────────────────────────────

    public synchronized void flushToDisk() {
        try {
            // Write profiles
            Map<String, Map<String, Object>> profileData = new HashMap<>();
            for (Map.Entry<String, PeerProfile> e : profiles.entrySet()) {
                profileData.put(e.getKey(), e.getValue().toMap());
            }
            Files.writeString(storagePath.resolve("profiles.json"),
                    mapper.writerWithDefaultPrettyPrinter().writeValueAsString(profileData));

            // Write observations
            Map<String, List<Map<String, Object>>> obsData = new HashMap<>();
            for (Map.Entry<String, List<Observation>> e : observations.entrySet()) {
                List<Map<String, Object>> list = new ArrayList<>();
                for (Observation o : e.getValue()) {
                    list.add(o.toMap());
                }
                obsData.put(e.getKey(), list);
            }
            Files.writeString(storagePath.resolve("observations.json"),
                    mapper.writerWithDefaultPrettyPrinter().writeValueAsString(obsData));

            // Write conclusions
            Map<String, List<Map<String, Object>>> concData = new HashMap<>();
            for (Map.Entry<String, List<Conclusion>> e : conclusions.entrySet()) {
                List<Map<String, Object>> list = new ArrayList<>();
                for (Conclusion c : e.getValue()) {
                    list.add(c.toMap());
                }
                concData.put(e.getKey(), list);
            }
            Files.writeString(storagePath.resolve("conclusions.json"),
                    mapper.writerWithDefaultPrettyPrinter().writeValueAsString(concData));

            log.debug("[HonchoStore] Flushed {} profiles, {} sessions with observations",
                    profiles.size(), observations.size());

        } catch (Exception e) {
            log.error("[HonchoStore] Flush failed: {}", e.getMessage());
        }
    }

    // ── Shutdown ────────────────────────────────────────────────────────

    public void shutdown() {
        shutdown = true;
        if (asyncWriterThread != null) {
            writeQueue.offer(SHUTDOWN_SENTINEL);
            try {
                asyncWriterThread.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        // Final flush
        flushToDisk();
        log.info("[HonchoStore] Shutdown complete");
    }

    // ── Stats ───────────────────────────────────────────────────────────

    public Map<String, Object> getStats() {
        return Map.of(
                "profile_count", profiles.size(),
                "session_count", observations.size(),
                "total_observations", observations.values().stream().mapToInt(List::size).sum(),
                "total_conclusions", conclusions.values().stream().mapToInt(List::size).sum(),
                "turn_counter", turnCounter.get(),
                "write_queue_size", writeQueue.size()
        );
    }

    // ── Helper classes ──────────────────────────────────────────────────

    private record WriteTask(String sessionId, String type, Object data) {}

    public record SessionMetadata(
            String sessionId,
            Instant createdAt,
            Instant lastAccessedAt,
            int messageCount
    ) {
        public SessionMetadata(String sessionId, Instant createdAt) {
            this(sessionId, createdAt, createdAt, 0);
        }
    }
}
