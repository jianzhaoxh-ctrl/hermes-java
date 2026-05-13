package com.hermes.agent.acp.server;

import com.hermes.agent.Agent;
import com.hermes.agent.acp.model.ACPProtocol;
import com.hermes.agent.acp.model.ACPModels.*;
import com.hermes.agent.acp.session.ACPSession;
import com.hermes.agent.acp.session.ACPSessionManager;
import com.hermes.agent.tools.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * Slash command handler for ACP sessions.
 *
 * <p>Handles commands like /help, /model, /tools, /context, /reset, /compact, /version.
 * Returns null for unrecognized commands so they fall through to the LLM.
 *
 * <p>Reference: Python acp_adapter/server.py _handle_slash_command and _cmd_* methods
 */
@Component
public class SlashCommandHandler {

    private static final Logger log = LoggerFactory.getLogger(SlashCommandHandler.class);

    /** Command descriptions for /help output */
    private static final Map<String, String> COMMAND_DESCRIPTIONS = new LinkedHashMap<>();
    static {
        COMMAND_DESCRIPTIONS.put("help", "Show available commands");
        COMMAND_DESCRIPTIONS.put("model", "Show or change current model");
        COMMAND_DESCRIPTIONS.put("tools", "List available tools");
        COMMAND_DESCRIPTIONS.put("context", "Show conversation context info");
        COMMAND_DESCRIPTIONS.put("reset", "Clear conversation history");
        COMMAND_DESCRIPTIONS.put("compact", "Compress conversation context");
        COMMAND_DESCRIPTIONS.put("version", "Show Hermes version");
    }

    private final ToolRegistry toolRegistry;

    public SlashCommandHandler(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    /**
     * Dispatch a slash command and return the response text.
     *
     * @param text   the raw command text (e.g. "/model gpt-4")
     * @param session the current ACP session
     * @param sessionManager the session manager for persistence
     * @return response text, or null if the command is unrecognized (let LLM handle it)
     */
    public String handle(String text, ACPSession session, ACPSessionManager sessionManager) {
        String[] parts = text.split("\\s+", 2);
        String cmd = parts[0].replaceFirst("^/+", "").toLowerCase();
        String args = parts.length > 1 ? parts[1].strip() : "";

        return switch (cmd) {
            case ACPProtocol.CMD_HELP -> cmdHelp();
            case ACPProtocol.CMD_MODEL -> cmdModel(args, session, sessionManager);
            case ACPProtocol.CMD_TOOLS -> cmdTools();
            case ACPProtocol.CMD_CONTEXT -> cmdContext(session);
            case ACPProtocol.CMD_RESET -> cmdReset(session, sessionManager);
            case ACPProtocol.CMD_COMPACT -> cmdCompact(session, sessionManager);
            case ACPProtocol.CMD_VERSION -> cmdVersion();
            default -> null; // Not a known command — let the LLM handle it
        };
    }

    /**
     * Build the list of available commands for ACP advertisement.
     */
    public List<AvailableCommand> getAvailableCommands() {
        List<AvailableCommand> commands = new ArrayList<>();
        for (Map.Entry<String, String> entry : COMMAND_DESCRIPTIONS.entrySet()) {
            String name = entry.getKey();
            String desc = entry.getValue();
            String hint = switch (name) {
                case "model" -> "model name to switch to";
                default -> null;
            };
            commands.add(new AvailableCommand(name, desc, Optional.ofNullable(hint)));
        }
        return commands;
    }

    // ================================================================
    //  Individual command implementations
    // ================================================================

    private String cmdHelp() {
        List<String> lines = new ArrayList<>();
        lines.add("Available commands:");
        lines.add("");
        for (Map.Entry<String, String> entry : COMMAND_DESCRIPTIONS.entrySet()) {
            lines.add(String.format("  /%-10s  %s", entry.getKey(), entry.getValue()));
        }
        lines.add("");
        lines.add("Unrecognized /commands are sent to the model as normal messages.");
        return String.join("\n", lines);
    }

    private String cmdModel(String args, ACPSession session, ACPSessionManager sessionManager) {
        if (args.isEmpty()) {
            String model = session.getModel() != null && !session.getModel().isEmpty()
                    ? session.getModel() : "unknown";
            return "Current model: " + model;
        }

        // Switch model
        String newModel = args.strip();
        session.setModel(newModel);
        sessionManager.saveSession(session.getSessionId());
        log.info("Session {}: model switched to {}", session.getSessionId(), newModel);
        return "Model switched to: " + newModel;
    }

    private String cmdTools() {
        try {
            Map<String, ToolRegistry.ToolSpec> specs = toolRegistry.getAllToolSpecs();
            if (specs == null || specs.isEmpty()) {
                return "No tools available.";
            }
            List<String> lines = new ArrayList<>();
            lines.add(String.format("Available tools (%d):", specs.size()));
            for (ToolRegistry.ToolSpec spec : specs.values()) {
                String name = spec.getName();
                String desc = spec.getDescription();
                if (desc != null && desc.length() > 80) {
                    desc = desc.substring(0, 77) + "...";
                }
                lines.add(String.format("  %s: %s", name, desc != null ? desc : ""));
            }
            return String.join("\n", lines);
        } catch (Exception e) {
            return "Could not list tools: " + e.getMessage();
        }
    }

    private String cmdContext(ACPSession session) {
        int nMessages = session.getHistorySize();
        if (nMessages == 0) {
            return "Conversation is empty (no messages yet).";
        }

        // Count by role
        Map<String, Integer> roles = new LinkedHashMap<>();
        for (Map<String, Object> msg : session.getHistory()) {
            String role = (String) msg.getOrDefault("role", "unknown");
            roles.merge(role, 1, Integer::sum);
        }

        List<String> lines = new ArrayList<>();
        lines.add(String.format("Conversation: %d messages", nMessages));
        lines.add(String.format("  user: %d, assistant: %d, tool: %d, system: %d",
                roles.getOrDefault("user", 0),
                roles.getOrDefault("assistant", 0),
                roles.getOrDefault("tool", 0),
                roles.getOrDefault("system", 0)));

        String model = session.getModel();
        if (model != null && !model.isEmpty()) {
            lines.add("Model: " + model);
        }
        return String.join("\n", lines);
    }

    private String cmdReset(ACPSession session, ACPSessionManager sessionManager) {
        session.clearHistory();
        sessionManager.saveSession(session.getSessionId());
        return "Conversation history cleared.";
    }

    private String cmdCompact(ACPSession session, ACPSessionManager sessionManager) {
        if (session.getHistorySize() == 0) {
            return "Nothing to compress — conversation is empty.";
        }
        // TODO: Integrate with Agent's SummaryManager for real compression
        // For now, we keep system messages and the last user/assistant pair
        int originalCount = session.getHistorySize();
        List<Map<String, Object>> history = session.getHistory();
        List<Map<String, Object>> compressed = new ArrayList<>();

        // Keep system messages
        for (Map<String, Object> msg : history) {
            if ("system".equals(msg.get("role"))) {
                compressed.add(msg);
            }
        }

        // Keep last user + assistant pair
        List<Map<String, Object>> recent = new ArrayList<>();
        for (int i = history.size() - 1; i >= 0 && recent.size() < 2; i--) {
            Map<String, Object> msg = history.get(i);
            if ("user".equals(msg.get("role")) || "assistant".equals(msg.get("role"))) {
                recent.add(0, msg);
            }
        }
        compressed.addAll(recent);

        session.setHistory(compressed);
        sessionManager.saveSession(session.getSessionId());

        return String.format("Context compressed: %d -> %d messages", originalCount, compressed.size());
    }

    private String cmdVersion() {
        return "Hermes Agent v1.0.0";
    }
}
