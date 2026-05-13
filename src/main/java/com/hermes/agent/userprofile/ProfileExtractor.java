package com.hermes.agent.userprofile;

import com.hermes.agent.llm.LLMService;
import com.hermes.agent.model.Message;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM-powered profile extractor — extracts structured facts from conversations.
 *
 * <p>Replaces the old keyword-based extraction with multi-pass LLM inference.
 * Runs asynchronously after each turn. Extracted facts are stored as
 * {@link Conclusion}s in the {@link PeerProfile}.
 *
 * <p>Extraction triggers:
 * <ul>
 *   <li>Every 3 turns (cadence: 3)</li>
 *   <li>When conversation count crosses a threshold (10, 25, 50...)</li>
 *   <li>On-demand via ProfileExtractor.extractNow()</li>
 * </ul>
 *
 * <p>Extraction categories:
 * <ul>
 *   <li><b>identity</b>: name, role, company, location, timezone</li>
 *   <li><b>preferences</b>: communication style, language, topic interests</li>
 *   <li><b>working_style</b>: problem-solving approach, constraints, tools used</li>
 *   <li><b>current_project</b>: active work, goals, blockers</li>
 *   <li><b>communication</b>: formal/informal, Chinese/English, response patterns</li>
 * </ul>
 */
@Component
public class ProfileExtractor {

    private static final Logger log = LoggerFactory.getLogger(ProfileExtractor.class);

    private final LLMService llmService;
    private final UserProfileConfig config;
    private final HonchoService honchoService;
    private final ExecutorService executor;

    /** Per-session extraction state */
    private final Map<String, ExtractionState> states = new ConcurrentHashMap<>();

    /** Extraction cadence (every N turns) */
    private static final int EXTRACTION_CADENCE = 3;

    /** Threshold for deep extraction */
    private static final int[] DEEP_THRESHOLDS = {10, 25, 50, 100};

    private boolean enabled = true;

    public ProfileExtractor(LLMService llmService, UserProfileConfig config, HonchoService honchoService) {
        this.llmService = llmService;
        this.config = config;
        this.honchoService = honchoService;
        this.executor = Executors.newFixedThreadPool(
                2,
                r -> { Thread t = new Thread(r, "hermes-profile-extractor"); t.setDaemon(true); return t; }
        );
    }

    @PostConstruct
    public void init() {
        log.info("[ProfileExtractor] Initialized (cadence={})", EXTRACTION_CADENCE);
    }

    // ── State ───────────────────────────────────────────────────────────

    private record ExtractionState(
            int turnCount,
            int lastExtractionTurn,
            int conversationCount,
            boolean inProgress
    ) {
        static ExtractionState initial() {
            return new ExtractionState(0, -999, 0, false);
        }

        ExtractionState advance() {
            return new ExtractionState(turnCount + 1, lastExtractionTurn, conversationCount, inProgress);
        }

        ExtractionState markExtracting() {
            return new ExtractionState(turnCount, turnCount, conversationCount, true);
        }

        ExtractionState markDone() {
            return new ExtractionState(turnCount, turnCount, conversationCount, false);
        }
    }

    // ── Entry point: onTurn ─────────────────────────────────────────────

    /**
     * Called after each turn to check if extraction should run.
     */
    public void onTurn(String sessionId, String userMessage, String assistantMessage) {
        if (!enabled) return;

        ExtractionState s = states.computeIfAbsent(sessionId, k -> ExtractionState.initial());
        ExtractionState next = s.advance();

        // Check cadence
        int turnsSince = next.turnCount() - next.lastExtractionTurn();
        boolean cadenceDue = turnsSince >= EXTRACTION_CADENCE;

        // Check deep threshold
        boolean thresholdDue = false;
        int newCount = next.conversationCount() + 1;
        for (int t : DEEP_THRESHOLDS) {
            if (newCount == t) { thresholdDue = true; break; }
        }

        // Skip if already in progress
        if (next.inProgress()) {
            states.put(sessionId, next);
            return;
        }

        states.put(sessionId, next);

        final boolean shouldExtractThreshold = thresholdDue;
        if (cadenceDue || thresholdDue) {
            executor.submit(() -> extractAndStore(sessionId, shouldExtractThreshold));
        }
    }

    /**
     * Run extraction on demand (for CLI / manual trigger).
     */
    public void extractNow(String sessionId) {
        executor.submit(() -> extractAndStore(sessionId, false));
    }

    // ── Core extraction logic ───────────────────────────────────────────

    private void extractAndStore(String sessionId, boolean deep) {
        try {
            ExtractionState s = states.get(sessionId);
            if (s != null) {
                states.put(sessionId, s.markExtracting());
            }

            log.debug("[ProfileExtractor] Starting extraction for session={}, deep={}", sessionId, deep);

            // Build context from recent observations
            List<Observation> observations = getRecentObservations(sessionId, 10);
            if (observations.isEmpty()) {
                log.debug("[ProfileExtractor] No observations for session={}", sessionId);
                return;
            }

            // Build prompt
            String prompt = buildExtractionPrompt(observations, deep);

            // Call LLM
            String result = callExtractionLLM(sessionId, prompt, deep);

            if (result == null || result.isBlank()) {
                log.debug("[ProfileExtractor] Empty result for session={}", sessionId);
                return;
            }

            // Parse and store conclusions
            List<String> facts = parseFacts(result);
            log.info("[ProfileExtractor] Extracted {} facts for session={}", facts.size(), sessionId);

            // Store as conclusions in HonchoService
            String peerId = resolveUserPeerId(sessionId);
            for (String fact : facts) {
                honchoService.addConclusion(sessionId, peerId, fact, deep ? "deep_extraction" : "extraction");
            }

            // Update representation if deep
            if (deep && !facts.isEmpty()) {
                updateRepresentation(sessionId, peerId, observations, facts);
            }

        } catch (Exception e) {
            log.warn("[ProfileExtractor] Extraction failed for session={}: {}", sessionId, e.getMessage());
        } finally {
            ExtractionState s = states.get(sessionId);
            if (s != null) states.put(sessionId, s.markDone());
        }
    }

    /**
     * Build the extraction prompt from recent observations.
     */
    private String buildExtractionPrompt(List<Observation> observations, boolean deep) {
        StringBuilder sb = new StringBuilder();
        sb.append("Analyze the following conversation and extract factual statements about the user.\n\n");
        sb.append("For each fact, output a single line starting with '- '.\n");
        sb.append("Group facts by category: [identity], [preferences], [working_style], [current_project], [communication].\n\n");
        sb.append("Rules:\n");
        sb.append("- Only extract facts directly supported by the conversation.\n");
        sb.append("- No speculation or inference beyond what's clearly stated.\n");
        sb.append("- Use the user's own words when possible.\n");
        sb.append("- [identity]: name, role, company, location, timezone, experience level\n");
        sb.append("- [preferences]: preferred languages, topics of interest, tools they like\n");
        sb.append("- [working_style]: how they approach problems, constraints, debugging style\n");
        sb.append("- [current_project]: what they're actively working on, goals, blockers\n");
        sb.append("- [communication]: formal/informal, Chinese/English, brief/detailed\n\n");

        if (deep) {
            sb.append("DEEP EXTRACTION — also extract subtle patterns, unspoken preferences, and potential needs.\n\n");
        }

        sb.append("Recent conversation:\n");
        for (Observation obs : observations) {
            String label = obs.isFromUser() ? "[user]" : "[assistant]";
            String snippet = obs.content().substring(0, Math.min(300, obs.content().length()));
            sb.append(label).append(" ").append(snippet).append("\n");
        }

        return sb.toString();
    }

    /**
     * Call LLM for extraction.
     */
    private String callExtractionLLM(String sessionId, String prompt, boolean deep) {
        String systemPrompt = deep
                ? "You are a perceptive analyst. Extract precise factual statements from the conversation. Be thorough and specific. Output only facts, one per line."
                : "You are a helpful analyst. Extract key factual statements from the conversation. Be concise. Output only facts, one per line.";

        List<Message> messages = List.of(
                new Message("system", systemPrompt, Instant.now()),
                new Message("user", prompt, Instant.now())
        );

        try {
            Message response = llmService.chat(messages, sessionId)
                    .timeout(Duration.ofSeconds(20))
                    .block();
            return response != null && response.getContent() != null
                    ? response.getContent().trim() : "";
        } catch (Exception e) {
            log.debug("[ProfileExtractor] LLM call failed: {}", e.getMessage());
            return "";
        }
    }

    /**
     * Parse LLM output into a list of facts.
     * Handles both grouped format ([category] - fact) and plain lines (- fact).
     */
    private List<String> parseFacts(String output) {
        List<String> facts = new ArrayList<>();
        if (output == null || output.isBlank()) return facts;

        String[] lines = output.split("\n");
        Pattern factPattern = Pattern.compile("^\\s*[-*•]\\s*(.+)$");
        Pattern groupedPattern = Pattern.compile("^\\s*\\[\\w+\\]\\s*[-*•]?\\s*(.+)$");

        for (String line : lines) {
            Matcher m = groupedPattern.matcher(line);
            if (m.matches()) {
                facts.add(m.group(1).trim());
            } else {
                m = factPattern.matcher(line);
                if (m.matches()) {
                    facts.add(m.group(1).trim());
                }
            }
        }

        return facts.stream()
                .filter(f -> f.length() > 10 && f.length() < 300)
                .toList();
    }

    /**
     * Update the peer representation with the extracted facts.
     */
    private void updateRepresentation(String sessionId, String peerId,
                                      List<Observation> observations, List<String> facts) {
        String prompt = "Based on these extracted facts about the user:\n\n" +
                String.join("\n", facts) +
                "\n\nWrite a brief (2-3 paragraph) representation of this user. " +
                "Include: who they are, how they work, what matters to them. " +
                "Write in third person. This will be used by an AI assistant to understand the user.";

        String systemPrompt = "You are a user representation writer. Write a concise, warm, " +
                "and accurate representation of the user. Focus on actionable insights. " +
                "Max 300 words. Third person.";

        List<Message> messages = List.of(
                new Message("system", systemPrompt, Instant.now()),
                new Message("user", prompt, Instant.now())
        );

        try {
            Message response = llmService.chat(messages, sessionId)
                    .timeout(Duration.ofSeconds(15))
                    .block();
            if (response != null && response.getContent() != null) {
                honchoService.getPeerProfile(sessionId, peerId)
                        .ifPresent(p -> p.updateRepresentation(response.getContent().trim()));
                log.debug("[ProfileExtractor] Representation updated for session={}", sessionId);
            }
        } catch (Exception e) {
            log.debug("[ProfileExtractor] Representation update failed: {}", e.getMessage());
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────

    private List<Observation> getRecentObservations(String sessionId, int limit) {
        return honchoService.getPeerProfile(sessionId, resolveUserPeerId(sessionId))
                .map(p -> {
                    List<Observation> all = p.getObservations();
                    if (all.isEmpty()) return List.<Observation>of();
                    int start = Math.max(0, all.size() - limit);
                    return all.subList(start, all.size());
                })
                .orElse(List.of());
    }

    private String resolveUserPeerId(String sessionId) {
        return honchoService.getUserProfile(sessionId)
                .map(com.hermes.agent.userprofile.PeerProfile::getPeerId)
                .orElse("user");
    }

    // ── Config ────────────────────────────────────────────────────────

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int getStateCount() {
        return states.size();
    }
}
