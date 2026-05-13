package com.hermes.agent.acp.model;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * ACP Request/Response models for JSON-RPC communication.
 * Maps to Python acp.schema InitializeResponse, PromptResponse, etc.
 */
public sealed interface ACPModels
        permits ACPModels.InitializeRequest, ACPModels.InitializeResponse,
                ACPModels.PromptRequest, ACPModels.PromptResponse,
                ACPModels.NewSessionRequest, ACPModels.NewSessionResponse,
                ACPModels.LoadSessionRequest, ACPModels.LoadSessionResponse,
                ACPModels.ResumeSessionRequest, ACPModels.ResumeSessionResponse,
                ACPModels.ForkSessionRequest, ACPModels.ForkSessionResponse,
                ACPModels.ListSessionsRequest, ACPModels.ListSessionsResponse,
                ACPModels.CancelRequest,
                ACPModels.SetSessionModelRequest, ACPModels.SetSessionModelResponse,
                ACPModels.SetSessionModeRequest, ACPModels.SetSessionModeResponse,
                ACPModels.SetConfigOptionRequest, ACPModels.SetConfigOptionResponse,
                ACPModels.SessionUpdate, ACPModels.Usage {

    // ---- Initialize ----------------------------------------------------------

    record InitializeRequest(
            Optional<Integer> protocolVersion,
            Optional<ClientCapabilities> clientCapabilities,
            Optional<Implementation> clientInfo
    ) implements ACPModels {}

    record InitializeResponse(
            int protocolVersion,
            Implementation agentInfo,
            AgentCapabilities agentCapabilities,
            List<AuthMethod> authMethods
    ) implements ACPModels {}

    record ClientCapabilities(
            Optional<String> sampling,
            Optional<Boolean> rollback,
            Optional<List<String>> tools,
            Optional<SessionCapabilities> session
    ) {}

    record SessionCapabilities(
            Optional<Boolean> list,
            Optional<Boolean> fork,
            Optional<Boolean> resume
    ) {}

    record AgentCapabilities(
            Optional<Boolean> streaming,
            Optional<SessionCapabilities> session
    ) {}

    record Implementation(
            String name,
            String version
    ) {}

    record AuthMethod(
            String id,
            String name,
            Optional<String> description
    ) {}

    // ---- Prompt --------------------------------------------------------------

    record PromptRequest(
            List<ACPContentBlock> prompt,
            String sessionId,
            Optional<Map<String, Object>> systemPrompt
    ) implements ACPModels {}

    record PromptResponse(
            String stopReason,
            Optional<Usage> usage,
            Optional<String> model
    ) implements ACPModels {}

    record Usage(
            long inputTokens,
            long outputTokens,
            long totalTokens,
            Optional<Long> thoughtTokens,
            Optional<Long> cachedReadTokens
    ) implements ACPModels {}

    // ---- Session lifecycle ----------------------------------------------------

    record NewSessionRequest(
            String cwd,
            Optional<List<McpServer>> mcpServers
    ) implements ACPModels {}

    record NewSessionResponse(
            String sessionId,
            Optional<SessionModelState> models
    ) implements ACPModels {}

    record LoadSessionRequest(
            String cwd,
            String sessionId,
            Optional<List<McpServer>> mcpServers
    ) implements ACPModels {}

    record LoadSessionResponse(
            Optional<SessionModelState> models
    ) implements ACPModels {}

    record ResumeSessionRequest(
            String cwd,
            String sessionId,
            Optional<List<McpServer>> mcpServers
    ) implements ACPModels {}

    record ResumeSessionResponse(
            Optional<SessionModelState> models
    ) implements ACPModels {}

    record ForkSessionRequest(
            String cwd,
            String sessionId,
            Optional<List<McpServer>> mcpServers
    ) implements ACPModels {}

    record ForkSessionResponse(
            String sessionId
    ) implements ACPModels {}

    record ListSessionsRequest(
            Optional<String> cursor,
            Optional<String> cwd
    ) implements ACPModels {}

    record ListSessionsResponse(
            List<SessionInfo> sessions,
            Optional<String> nextCursor
    ) implements ACPModels {}

    record CancelRequest(
            String sessionId
    ) implements ACPModels {}

    record SessionInfo(
            String sessionId,
            String cwd,
            Optional<String> title,
            Optional<String> updatedAt
    ) {}

    // ---- Model selection ------------------------------------------------------

    record SessionModelState(
            List<ModelInfo> availableModels,
            String currentModelId
    ) {}

    record ModelInfo(
            String modelId,
            String name,
            Optional<String> description
    ) {}

    // ---- Model/mode/config switching -----------------------------------------

    record SetSessionModelRequest(
            String modelId,
            String sessionId
    ) implements ACPModels {}

    record SetSessionModelResponse() implements ACPModels {}

    record SetSessionModeRequest(
            String modeId,
            String sessionId
    ) implements ACPModels {}

    record SetSessionModeResponse() implements ACPModels {}

    record SetConfigOptionRequest(
            String configId,
            String sessionId,
            String value
    ) implements ACPModels {}

    record SetConfigOptionResponse(
            List<String> configOptions
    ) implements ACPModels {}

    // ---- MCP Server ----------------------------------------------------------

    record McpServer(
            String name,
            String transport,     // "stdio" | "http" | "sse"
            Optional<String> command,
            Optional<List<String>> args,
            Optional<Map<String, String>> env,
            Optional<String> url,
            Optional<Map<String, String>> headers
    ) {}

    // ---- Session updates (server -> client) ----------------------------------

    record SessionUpdate(
            String sessionUpdate,   // discriminator field
            Object content
    ) implements ACPModels {}

    /** AvailableCommandsUpdate content */
    record AvailableCommandsUpdateContent(
            List<AvailableCommand> availableCommands
    ) {}

    record AvailableCommand(
            String name,
            String description,
            Optional<String> inputHint
    ) {}
}