package com.hermes.agent.acp.model;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * ACP Content Block - represents a piece of content in the protocol.
 * 
 * Maps to Python: TextContentBlock, ImageContentBlock, AudioContentBlock,
 * ResourceContentBlock, EmbeddedResourceContentBlock from acp.schema
 */
public sealed interface ACPContentBlock
        permits ACPContentBlock.Text, ACPContentBlock.Image,
                ACPContentBlock.Audio, ACPContentBlock.Resource,
                ACPContentBlock.ToolUse, ACPContentBlock.ToolResult,
                ACPContentBlock.Pause {

    String type();

    // ---- Text content block --------------------------------------------------

    record Text(
            String text
    ) implements ACPContentBlock {
        public static final String TYPE = "text";
        public static Text of(String text) { return new Text(text); }
        @Override public String type() { return TYPE; }
    }

    // ---- Image content block -------------------------------------------------

    record Image(
            String url,
            String mediaType,
            Optional<Integer> width,
            Optional<Integer> height
    ) implements ACPContentBlock {
        public static final String TYPE = "image";
        public static Image of(String url) { return new Image(url, null, Optional.empty(), Optional.empty()); }
        public static Image of(String url, String mediaType) { return new Image(url, mediaType, Optional.empty(), Optional.empty()); }
        @Override public String type() { return TYPE; }
    }

    // ---- Audio content block -------------------------------------------------

    record Audio(
            String url,
            String mediaType,
            Optional<Integer> durationMs
    ) implements ACPContentBlock {
        public static final String TYPE = "audio";
        public static Audio of(String url) { return new Audio(url, null, Optional.empty()); }
        @Override public String type() { return TYPE; }
    }

    // ---- Resource content block ----------------------------------------------

    record Resource(
            String url,
            String mimeType,
            Optional<String> title,
            Optional<String> description
    ) implements ACPContentBlock {
        public static final String TYPE = "resource";
        @Override public String type() { return TYPE; }
    }

    // ---- Tool use block -------------------------------------------------------

    record ToolUse(
            String toolCallId,
            String toolName,
            Map<String, Object> input
    ) implements ACPContentBlock {
        public static final String TYPE = "tool_use";
        @Override public String type() { return TYPE; }
    }

    // ---- Tool result block ---------------------------------------------------

    record ToolResult(
            String toolCallId,
            String content,
            boolean isError
    ) implements ACPContentBlock {
        public static final String TYPE = "tool_result";
        @Override public String type() { return TYPE; }
    }

    // ---- Pause block ---------------------------------------------------------

    record Pause(
            String reason
    ) implements ACPContentBlock {
        public static final String TYPE = "pause";
        @Override public String type() { return TYPE; }
    }

    // ---- Tool call related records -------------------------------------------

    /** Tool call location (file/line reference) */
    record ToolLocation(
            String path,
            Optional<Integer> line,
            Optional<Integer> column
    ) {}

    /** Tool call start event content */
    record ToolCallStartContent(
            String title,
            String kind,           // ToolKind: read/edit/search/execute/fetch/think/other
            List<ACPContentBlock> content,
            List<ToolLocation> locations,
            Map<String, Object> rawInput
    ) {}

    /** Tool call progress/result content */
    record ToolCallProgressContent(
            String kind,
            String status,         // "completed" / "in_progress" / "error"
            List<ACPContentBlock> content,
            Map<String, Object> rawOutput
    ) {}
}