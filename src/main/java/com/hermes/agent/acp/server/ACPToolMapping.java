package com.hermes.agent.acp.server;

import com.hermes.agent.acp.model.ACPProtocol;
import com.hermes.agent.acp.model.ACPContentBlock;
import com.hermes.agent.acp.model.ACPContentBlock.*;

import java.util.*;

/**
 * Maps Hermes tool names to ACP ToolKind values and builds tool call content.
 *
 * <p>Reference: Python acp_adapter/tools.py
 */
public final class ACPToolMapping {

    /** Maps hermes tool names to ACP ToolKind values */
    private static final Map<String, String> TOOL_KIND_MAP = new LinkedHashMap<>();
    static {
        TOOL_KIND_MAP.put("read_file", ACPProtocol.TOOL_KIND_READ);
        TOOL_KIND_MAP.put("read", ACPProtocol.TOOL_KIND_READ);
        TOOL_KIND_MAP.put("search_files", ACPProtocol.TOOL_KIND_SEARCH);
        TOOL_KIND_MAP.put("search", ACPProtocol.TOOL_KIND_SEARCH);
        TOOL_KIND_MAP.put("grep", ACPProtocol.TOOL_KIND_SEARCH);
        TOOL_KIND_MAP.put("edit_file", ACPProtocol.TOOL_KIND_EDIT);
        TOOL_KIND_MAP.put("edit", ACPProtocol.TOOL_KIND_EDIT);
        TOOL_KIND_MAP.put("patch", ACPProtocol.TOOL_KIND_EDIT);
        TOOL_KIND_MAP.put("write_file", ACPProtocol.TOOL_KIND_EDIT);
        TOOL_KIND_MAP.put("terminal", ACPProtocol.TOOL_KIND_EXECUTE);
        TOOL_KIND_MAP.put("execute", ACPProtocol.TOOL_KIND_EXECUTE);
        TOOL_KIND_MAP.put("shell", ACPProtocol.TOOL_KIND_EXECUTE);
        TOOL_KIND_MAP.put("bash", ACPProtocol.TOOL_KIND_EXECUTE);
        TOOL_KIND_MAP.put("fetch", ACPProtocol.TOOL_KIND_FETCH);
        TOOL_KIND_MAP.put("web_fetch", ACPProtocol.TOOL_KIND_FETCH);
        TOOL_KIND_MAP.put("curl", ACPProtocol.TOOL_KIND_FETCH);
        TOOL_KIND_MAP.put("think", ACPProtocol.TOOL_KIND_THINK);
        TOOL_KIND_MAP.put("thinking", ACPProtocol.TOOL_KIND_THINK);
    }

    private ACPToolMapping() {}

    /**
     * Get the ACP ToolKind for a hermes tool name.
     * Returns TOOL_KIND_OTHER for unrecognized tools.
     */
    public static String getToolKind(String toolName) {
        return TOOL_KIND_MAP.getOrDefault(toolName, ACPProtocol.TOOL_KIND_OTHER);
    }

    /**
     * Generate a unique tool call ID.
     */
    public static String makeToolCallId() {
        return "tc_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    /**
     * Build a display title for a tool call.
     */
    public static String buildToolTitle(String toolName, Map<String, Object> args) {
        return switch (toolName) {
            case "read_file", "read" -> "Reading " + extractPath(args);
            case "edit_file", "edit", "patch" -> "Editing " + extractPath(args);
            case "write_file" -> "Writing " + extractPath(args);
            case "search_files", "search", "grep" -> "Searching" + extractPattern(args);
            case "terminal", "execute", "shell", "bash" -> "Running command";
            case "fetch", "web_fetch", "curl" -> "Fetching " + extractUrl(args);
            case "think", "thinking" -> "Thinking";
            default -> "Running " + toolName;
        };
    }

    /**
     * Build tool call start content for ACP session update.
     */
    public static ToolCallStartContent buildToolStartContent(
            String toolName, Map<String, Object> args) {
        String kind = getToolKind(toolName);
        String title = buildToolTitle(toolName, args);
        List<ACPContentBlock> content = buildStartBlocks(toolName, args);
        List<ToolLocation> locations = extractLocations(toolName, args);
        return new ToolCallStartContent(title, kind, content, locations, args);
    }

    /**
     * Build tool call completion content for ACP session update.
     */
    public static ToolCallProgressContent buildToolCompleteContent(
            String toolName, String result, Map<String, Object> args) {
        String kind = getToolKind(toolName);
        List<ACPContentBlock> content = new ArrayList<>();
        if (result != null && !result.isEmpty()) {
            // Truncate very long results
            String display = result.length() > 2000
                    ? result.substring(0, 1997) + "..."
                    : result;
            content.add(ACPContentBlock.Text.of(display));
        }
        Map<String, Object> rawOutput = new LinkedHashMap<>();
        rawOutput.put("result", result);
        return new ToolCallProgressContent(kind, "completed", content, rawOutput);
    }

    // ================================================================
    //  Private helpers
    // ================================================================

    private static List<ACPContentBlock> buildStartBlocks(String toolName, Map<String, Object> args) {
        List<ACPContentBlock> blocks = new ArrayList<>();
        return switch (toolName) {
            case "read_file", "read" -> {
                String path = extractPath(args);
                if (path != null) blocks.add(ACPContentBlock.Text.of("File: " + path));
                yield blocks;
            }
            case "edit_file", "edit", "patch", "write_file" -> {
                String path = extractPath(args);
                if (path != null) blocks.add(ACPContentBlock.Text.of("File: " + path));
                yield blocks;
            }
            case "terminal", "execute", "shell", "bash" -> {
                Object cmd = args.get("command");
                if (cmd != null) blocks.add(ACPContentBlock.Text.of("$ " + cmd));
                yield blocks;
            }
            case "search_files", "search", "grep" -> {
                Object pattern = args.get("pattern");
                if (pattern != null) blocks.add(ACPContentBlock.Text.of("Pattern: " + pattern));
                yield blocks;
            }
            default -> blocks;
        };
    }

    private static List<ToolLocation> extractLocations(String toolName, Map<String, Object> args) {
        List<ToolLocation> locations = new ArrayList<>();
        String path = extractPath(args);
        if (path != null && (toolName.contains("read") || toolName.contains("edit")
                || toolName.contains("write") || toolName.contains("patch"))) {
            locations.add(new ToolLocation(path, Optional.empty(), Optional.empty()));
        }
        return locations;
    }

    private static String extractPath(Map<String, Object> args) {
        if (args == null) return null;
        Object path = args.getOrDefault("path", args.getOrDefault("file_path",
                args.getOrDefault("filepath", args.getOrDefault("file", null))));
        return path != null ? path.toString() : null;
    }

    private static String extractPattern(Map<String, Object> args) {
        if (args == null) return "";
        Object pattern = args.get("pattern");
        return pattern != null ? " for \"" + pattern + "\"" : "";
    }

    private static String extractUrl(Map<String, Object> args) {
        if (args == null) return "";
        Object url = args.getOrDefault("url", args.getOrDefault("endpoint", null));
        return url != null ? url.toString() : "";
    }
}
