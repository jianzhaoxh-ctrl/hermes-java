package com.hermes.agent.userprofile;

import com.hermes.agent.llm.LLMService;
import com.hermes.agent.model.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;

/**
 * Dialectic Reasoning Engine — the core of the Honcho-style user profiling.
 *
 * <p>Implements multi-pass LLM reasoning to build and maintain a deep
 * understanding of the user. Inspired by Honcho's dialectic Q&A system.
 *
 * <h3>Architecture</h3>
 * <ul>
 *   <li><b>Pass 0</b> (cold): "Who is this person?" — general user profile</li>
 *   <li><b>Pass 1</b> (warm): Self-audit — what gaps remain?</li>
 *   <li><b>Pass 2</b>: Reconciliation — reconcile contradictions across passes</li>
 * </ul>
 *
 * <h3>Cost management (B5)</h3>
 * <ul>
 *   <li>Cadence: runs every N turns (default 2), not every turn</li>
 *   <li>Depth: 1-3 passes per cycle, early exit if prior pass had strong signal</li>
 *   <li>Reasoning heuristic: longer queries get higher reasoning levels</li>
 *   <li>Stale result discarding: results older than cadence × 2 turns are discarded</li>
 * </ul>
 */
@Component
public class DialecticEngine {

    private static final Logger log = LoggerFactory.getLogger(DialecticEngine.class);

    // Reasoning level hierarchy
    static final List<String> LEVEL_ORDER = List.of("minimal", "low", "medium", "high", "max");

    // Query-length thresholds for reasoning heuristic
    static final int HEURISTIC_MEDIUM = 120;
    static final int HEURISTIC_HIGH = 400;

    // Stale result threshold multiplier
    static final int STALE_RESULT_MULTIPLIER = 2;

    private final LLMService llmService;
    private final UserProfileConfig config;

    // Per-session dialectic state
    private final Map<String, DialecticState> states = new ConcurrentHashMap<>();

    // Thread pool for background dialectic work
    private final ExecutorService executor = Executors.newFixedThreadPool(
            Math.max(2, Runtime.getRuntime().availableProcessors()),
            r -> {
                Thread t = new Thread(r, "hermes-dialectic");
                t.setDaemon(true);
                return t;
            }
    );

    public DialecticEngine(LLMService llmService, UserProfileConfig config) {
        this.llmService = llmService;
        this.config = config;
    }

    // ─────────────────────────────────────────────────────────────────────
    // State tracking per session
    // ─────────────────────────────────────────────────────────────────────

    public record DialecticState(
        int turnCount,
        int lastDialecticTurn,
        int lastContextTurn,
        int emptyStreak,
        String pendingResult,
        long pendingResultFiredAt,
        long threadStartTime,
        boolean threadAlive,
        int pendingVersion  // increment to invalidate
    ) {
        public static DialecticState initial() {
            return new DialecticState(0, -999, -999, 0, "", -999, 0, false, 0);
        }

        public DialecticState withTurn(int turn) {
            return new DialecticState(turn, lastDialecticTurn, lastContextTurn,
                    emptyStreak, pendingResult, pendingResultFiredAt,
                    threadStartTime, threadAlive, pendingVersion);
        }

        public DialecticState withDialecticRun(int turn, String result) {
            int newStreak = (result != null && !result.isBlank()) ? 0 : emptyStreak + 1;
            return new DialecticState(turn, turn, lastContextTurn,
                    newStreak, result != null ? result : "", Instant.now().toEpochMilli(),
                    0, false, pendingVersion + 1);
        }

        public DialecticState withContextRefresh(int turn) {
            return new DialecticState(turn, lastDialecticTurn, turn,
                    emptyStreak, pendingResult, pendingResultFiredAt,
                    threadStartTime, threadAlive, pendingVersion);
        }

        public DialecticState withThreadStart(long startTime) {
            return new DialecticState(turnCount, lastDialecticTurn, lastContextTurn,
                    emptyStreak, pendingResult, pendingResultFiredAt,
                    startTime, true, pendingVersion);
        }
    }

    private DialecticState getOrCreateState(String sessionId) {
        return states.computeIfAbsent(sessionId, k -> DialecticState.initial());
    }

    // ─────────────────────────────────────────────────────────────────────
    // Main entry: onTurnStart
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Called at the start of each turn. Tracks turn count.
     */
    public void onTurnStart(String sessionId) {
        DialecticState s = getOrCreateState(sessionId);
        states.put(sessionId, s.withTurn(s.turnCount() + 1));
    }

    /**
     * Queue background dialectic prefetch for the upcoming turn.
     * Checks cadence, starts thread if needed.
     */
    public void queueDialecticPrefetch(String sessionId, String userQuery) {
        if (!config.isContextInjectionEnabled()) return;
        if (isTrivialPrompt(userQuery)) return;

        DialecticState s = getOrCreateState(sessionId);

        // Thread-alive guard with stale thread recovery
        if (s.threadAlive && s.threadStartTime() > 0) {
            long age = Instant.now().toEpochMilli() - s.threadStartTime;
            long timeout = config.getDialecticMaxChars() / 100 * 1000L; // rough timeout
            if (age > timeout * 2) {
                log.debug("[Dialectic] Stale thread detected, age={}ms — resetting", age);
                s = new DialecticState(s.turnCount(), s.lastDialecticTurn(), s.lastContextTurn(),
                        s.emptyStreak(), s.pendingResult(), s.pendingResultFiredAt(), 0, false, s.pendingVersion());
            } else {
                log.debug("[Dialectic] Prefetch skipped: thread still alive");
                return;
            }
        }

        // Cadence gate
        int effectiveCadence = config.getEffectiveDialecticCadence(s.emptyStreak());
        int turnsSince = s.turnCount() - s.lastDialecticTurn();
        if (turnsSince < effectiveCadence) {
            log.debug("[Dialectic] Cadence not met: effective={}, turns_since={}",
                    effectiveCadence, turnsSince);
            return;
        }

        // Discard stale pending result
        if (s.pendingResultFiredAt() >= 0) {
            long staleLimit = effectiveCadence * STALE_RESULT_MULTIPLIER;
            if ((s.turnCount() - s.pendingResultFiredAt()) > staleLimit) {
                log.debug("[Dialectic] Discarding stale pending result (fired_at={}, turn={})",
                        s.pendingResultFiredAt(), s.turnCount());
                s = new DialecticState(s.turnCount(), s.lastDialecticTurn(), s.lastContextTurn(),
                        s.emptyStreak(), "", -999, 0, false, s.pendingVersion() + 1);
            }
        }

        // Fire background dialectic
        final DialecticState finalState = s;
        states.put(sessionId, s.withThreadStart(Instant.now().toEpochMilli()));

        executor.submit(() -> runDialecticBackground(sessionId, userQuery, finalState.turnCount()));
    }

    /**
     * Get the pending dialectic result and clear it (atomic consume).
     * Returns empty string if no result or result is stale.
     */
    public String consumeDialecticResult(String sessionId) {
        DialecticState s = getOrCreateState(sessionId);
        if (s.pendingResultFiredAt() < 0 || s.pendingResult().isBlank()) {
            return "";
        }

        // Check if result is stale
        int effectiveCadence = config.getEffectiveDialecticCadence(s.emptyStreak());
        long staleLimit = effectiveCadence * STALE_RESULT_MULTIPLIER;
        if ((s.turnCount() - s.pendingResultFiredAt()) > staleLimit) {
            log.debug("[Dialectic] Discarding stale result on consume (fired={}, turn={})",
                    s.pendingResultFiredAt(), s.turnCount());
            states.put(sessionId, new DialecticState(s.turnCount(), s.lastDialecticTurn(),
                    s.lastContextTurn(), s.emptyStreak(), "", -999, 0, false, s.pendingVersion() + 1));
            return "";
        }

        String result = s.pendingResult();
        states.put(sessionId, new DialecticState(s.turnCount(), s.lastDialecticTurn(),
                s.lastContextTurn(), s.emptyStreak(), "", -999, 0, false, s.pendingVersion() + 1));
        return result;
    }

    /**
     * Check if the thread is still alive.
     */
    public boolean isThreadAlive(String sessionId) {
        DialecticState s = getOrCreateState(sessionId);
        if (!s.threadAlive() || s.threadStartTime() == 0) return false;
        long age = Instant.now().toEpochMilli() - s.threadStartTime();
        return age < config.getDialecticMaxChars() / 100 * 2000L; // generous timeout
    }

    /**
     * Get liveness snapshot for diagnostics.
     */
    public Map<String, Object> getLivenessSnapshot(String sessionId) {
        DialecticState s = getOrCreateState(sessionId);
        long threadAge = s.threadAlive() && s.threadStartTime() > 0
                ? Instant.now().toEpochMilli() - s.threadStartTime() : null;
        return Map.of(
            "turn_count", s.turnCount(),
            "last_dialectic_turn", s.lastDialecticTurn(),
            "pending_result_fired_at", s.pendingResultFiredAt(),
            "empty_streak", s.emptyStreak(),
            "effective_cadence", config.getEffectiveDialecticCadence(s.emptyStreak()),
            "thread_alive", s.threadAlive(),
            "thread_age_ms", threadAge,
            "pending_version", s.pendingVersion()
        );
    }

    // ─────────────────────────────────────────────────────────────────────
    // Background dialectic run
    // ─────────────────────────────────────────────────────────────────────

    private void runDialecticBackground(String sessionId, String query, int firedAtTurn) {
        try {
            String result = runDialecticDepthSync(sessionId, query);
            DialecticState s = getOrCreateState(sessionId);
            int newStreak = result.isBlank() ? s.emptyStreak() + 1 : 0;
            states.put(sessionId, new DialecticState(
                    s.turnCount(), firedAtTurn, s.lastContextTurn(),
                    newStreak, result, firedAtTurn,
                    0, false, s.pendingVersion() + 1
            ));
            if (!result.isBlank()) {
                log.debug("[Dialectic] Result ready for turn {}: {} chars",
                        firedAtTurn, result.length());
            }
        } catch (Exception e) {
            log.debug("[Dialectic] Background run failed: {}", e.getMessage());
            DialecticState s = getOrCreateState(sessionId);
            states.put(sessionId, new DialecticState(
                    s.turnCount(), s.lastDialecticTurn(), s.lastContextTurn(),
                    s.emptyStreak() + 1, "", -999, 0, false, s.pendingVersion()
            ));
        }
    }

    /**
     * Synchronous multi-pass dialectic reasoning.
     * Runs 1-3 passes based on dialecticDepth config.
     */
    private String runDialecticDepthSync(String sessionId, String query) {
        boolean isCold = true; // TODO: check if session has prior context
        List<String> results = new ArrayList<>();

        for (int pass = 0; pass < config.getDialecticDepth(); pass++) {
            if (pass > 0 && hasStrongSignal(results.get(results.size() - 1))) {
                log.debug("[Dialectic] Pass {} skipped: prior pass had strong signal", pass);
                break;
            }

            String prompt = buildDialecticPrompt(pass, results, isCold, query);
            String level = resolvePassLevel(pass, query);

            log.debug("[Dialectic] Pass {}: level={}, prompt_len={}", pass, level, prompt.length());

            String result = callDialecticLLM(sessionId, prompt, level);
            results.add(result != null ? result : "");
        }

        // Return the last non-empty result
        for (int i = results.size() - 1; i >= 0; i--) {
            if (!results.get(i).isBlank()) return results.get(i);
        }
        return "";
    }

    /**
     * Build the dialectic prompt for a given pass.
     */
    private String buildDialecticPrompt(int pass, List<String> priorResults, boolean isCold, String currentQuery) {
        return switch (pass) {
            case 0 -> isCold
                    ? "Who is this person? What are their preferences, goals, and working style? " +
                      "Focus on facts that would help an AI assistant be immediately useful."
                    : "Given what's been discussed in this session so far (" +
                      (currentQuery.length() > 50 ? currentQuery.substring(0, 50) + "..." : currentQuery) +
                      "), what context about this user is most relevant to the current conversation? " +
                      "Prioritize active context over biographical facts.";
            case 1 -> {
                String prior = priorResults.isEmpty() ? "" : priorResults.get(priorResults.size() - 1);
                yield "Given this initial assessment:\n\n" + prior + "\n\n" +
                      "What gaps remain in your understanding that would help going forward? " +
                      "Synthesize what you actually know about the user's current state and " +
                      "immediate needs, grounded in evidence from recent sessions.";
            }
            default -> {
                // Pass 2+: reconciliation
                StringBuilder sb = new StringBuilder("Prior passes produced:\n\n");
                for (int i = 0; i < priorResults.size(); i++) {
                    sb.append("Pass ").append(i + 1).append(":\n")
                      .append(priorResults.get(i).isEmpty() ? "(empty)" : priorResults.get(i))
                      .append("\n\n");
                }
                sb.append("Do these assessments cohere? Reconcile any contradictions and " +
                          "produce a final, concise synthesis of what matters most.");
                yield sb.toString();
            }
        };
    }

    /**
     * Resolve the reasoning level for a given pass.
     * Respects dialecticDepthLevels config, proportional levels, and reasoning heuristic.
     */
    private String resolvePassLevel(int pass, String query) {
        // 1. Explicit per-pass config (dialecticDepthLevels) wins absolutely
        if (config.getDialecticDepthLevels() != null && pass < config.getDialecticDepthLevels().size()) {
            String explicit = config.getDialecticDepthLevels().get(pass);
            if (LEVEL_ORDER.contains(explicit)) return explicit;
        }

        String base = config.getDialecticReasoningLevel();
        if (base == null) base = "low";

        // 2. Proportional levels: depth > 1 gets lighter early passes
        // (1,0)→base, (2,0)→minimal, (2,1)→base, (3,0)→minimal, (3,1)→base, (3,2)→low
        String mapped = switch (config.getDialecticDepth()) {
            case 1 -> "base";
            case 2 -> pass == 0 ? "minimal" : "base";
            case 3 -> switch (pass) {
                case 0 -> "minimal";
                case 1 -> "base";
                default -> "low";
            };
            default -> "base";
        };

        if ("base".equals(mapped)) {
            // 3. Apply reasoning heuristic on base level
            mapped = applyReasoningHeuristic(base, query);
        }

        // 4. Clamp at reasoningLevelCap
        int capIdx = LEVEL_ORDER.indexOf(config.getReasoningLevelCap());
        int mappedIdx = LEVEL_ORDER.indexOf(mapped);
        if (mappedIdx < 0) mappedIdx = 1; // default to "low"
        if (capIdx < 0) capIdx = 3; // default to "high"
        return LEVEL_ORDER.get(Math.min(mappedIdx, capIdx));
    }

    /**
     * Scale reasoning level up by query length.
     * +1 level at HEURISTIC_MEDIUM chars, +2 at HEURISTIC_HIGH chars.
     */
    private String applyReasoningHeuristic(String base, String query) {
        if (!config.isReasoningHeuristic() || query == null || query.isBlank()) {
            return base;
        }
        int n = query.length();
        int bump = n < HEURISTIC_MEDIUM ? 0 : (n < HEURISTIC_HIGH ? 1 : 2);
        int baseIdx = LEVEL_ORDER.indexOf(base);
        int capIdx = LEVEL_ORDER.indexOf(config.getReasoningLevelCap());
        return LEVEL_ORDER.get(Math.min(baseIdx + bump, capIdx));
    }

    /**
     * Check if a dialectic result has strong signal (sufficient quality to skip further passes).
     */
    private boolean hasStrongSignal(String result) {
        if (result == null || result.length() < 100) return false;
        return result.length() > 300 || (
                result.contains("\n") && (
                        result.contains("##") ||
                        result.contains("•") ||
                        result.contains("- ") ||
                        Pattern.compile("^\\s*\\d+\\. ", Pattern.MULTILINE).matcher(result).find()
                )
        );
    }

    /**
     * Call the LLM for dialectic reasoning.
     * Uses a system prompt tailored for the reasoning level.
     */
    private String callDialecticLLM(String sessionId, String prompt, String level) {
        // Build system prompt based on reasoning level
        String systemPrompt = buildDialecticSystemPrompt(level);

        List<Message> messages = new ArrayList<>();
        messages.add(new Message("system", systemPrompt, Instant.now()));
        messages.add(new Message("user", prompt, Instant.now()));

        try {
            Mono<Message> result = llmService.chat(messages, sessionId);
            Message response = result.timeout(Duration.ofSeconds(30)).block();
            if (response != null && response.getContent() != null) {
                // Truncate to dialecticMaxChars
                String content = response.getContent();
                if (content.length() > config.getDialecticMaxChars()) {
                    content = content.substring(0, config.getDialecticMaxChars()) + " …";
                }
                return content;
            }
        } catch (Exception e) {
            log.debug("[Dialectic] LLM call failed: {}", e.getMessage());
        }
        return "";
    }

    /**
     * Build the system prompt for dialectic reasoning.
     * The level determines how thorough and cautious the reasoning should be.
     */
    private String buildDialecticSystemPrompt(String level) {
        String base = switch (level) {
            case "minimal" -> "You are a concise assistant. Provide brief, factual answers. " +
                              "Focus on key facts only. Max 3 sentences.";
            case "low" -> "You are a helpful assistant. Provide clear, straightforward answers. " +
                          "Focus on the most relevant facts. Keep it concise but informative.";
            case "medium" -> "You are a thoughtful assistant. Synthesize information across multiple " +
                             "observations. Consider contradictions and nuances. Provide a balanced view.";
            case "high" -> "You are a deep analyst. Examine behavioral patterns, contradictions, " +
                           "and implicit assumptions. Consider the user's goals and working style. " +
                           "Provide thorough, nuanced analysis.";
            case "max" -> "You are a thorough auditor. Leave no stone unturned. Examine everything " +
                          "from multiple angles. Consider long-term patterns, personality traits, " +
                          "communication preferences, and project history. Be exhaustive.";
            default -> "You are a helpful assistant. Provide clear, relevant analysis.";
        };

        return base + "\n\nAnalyze the user's messages and provide insights about their identity, " +
               "preferences, current projects, and working style. Format your response with " +
               "section headers (##) when covering multiple aspects.";
    }

    // ─────────────────────────────────────────────────────────────────────
    // Trivial prompt detection
    // ─────────────────────────────────────────────────────────────────────

    private static final Pattern TRIVIAL_PATTERN = Pattern.compile(
            "^(yes|no|ok|okay|sure|thanks|thank you|y|n|yep|nope|yeah|nah|" +
            "continue|go ahead|do it|proceed|got it|cool|nice|great|done|next|lgtm|k)$",
            Pattern.CASE_INSENSITIVE
    );

    private boolean isTrivialPrompt(String text) {
        if (text == null || text.isBlank()) return true;
        String trimmed = text.trim();
        if (trimmed.isEmpty()) return true;
        if (trimmed.startsWith("/")) return true;  // slash commands
        return TRIVIAL_PATTERN.matcher(trimmed).matches();
    }

    // ─────────────────────────────────────────────────────────────────────
    // Public API: run dialectic on demand
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Run dialectic reasoning on demand (for honcho_reasoning tool).
     * Does NOT respect cadence — always runs immediately.
     */
    public Mono<String> runDialecticOnDemand(String sessionId, String query, String reasoningLevel) {
        return Mono.fromCallable(() -> {
            String level = reasoningLevel != null && LEVEL_ORDER.contains(reasoningLevel)
                    ? reasoningLevel : config.getDialecticReasoningLevel();

            String prompt = "Answer this question about the user: " + query;
            String systemPrompt = buildDialecticSystemPrompt(level);

            List<Message> messages = List.of(
                    new Message("system", systemPrompt, Instant.now()),
                    new Message("user", prompt, Instant.now())
            );

            Message response = llmService.chat(messages, sessionId).block(Duration.ofSeconds(30));
            return response != null && response.getContent() != null
                    ? response.getContent() : "No result from dialectic engine.";
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Sync observation to dialectic state (for cost tracking).
     */
    public void syncDialecticState(String sessionId, int turnCount) {
        DialecticState s = getOrCreateState(sessionId);
        states.put(sessionId, s.withTurn(turnCount));
    }

    /**
     * Advance dialectic turn counter (called after each turn).
     */
    public void advanceTurn(String sessionId) {
        DialecticState s = getOrCreateState(sessionId);
        states.put(sessionId, s.withTurn(s.turnCount() + 1));
    }
}
