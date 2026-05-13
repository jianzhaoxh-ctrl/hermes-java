package com.hermes.agent.acp.model;

/**
 * ACP Protocol version and method constants.
 * Corresponds to Python acp package: acp/__init__.py, acp/schema.py
 */
public final class ACPProtocol {
    
    public static final int PROTOCOL_VERSION = 2;
    public static final String VERSION_STRING = "2.0.0";
    
    // ---- JSON-RPC 2.0 --------------------------------------------------------
    
    public static final String JSONRPC_VERSION = "2.0";
    public static final String JSONRPC_INVALID_REQUEST = "Invalid Request";
    public static final String JSONRPC_METHOD_NOT_FOUND = "Method not found";
    public static final String JSONRPC_INVALID_PARAMS = "Invalid params";
    public static final String JSONRPC_INTERNAL_ERROR = "Internal error";
    
    // ---- Session lifecycle methods -------------------------------------------
    
    public static final String METHOD_INITIALIZE = "initialize";
    public static final String METHOD_AUTHENTICATE = "authenticate";
    public static final String METHOD_NEW_SESSION = "newSession";
    public static final String METHOD_LOAD_SESSION = "loadSession";
    public static final String METHOD_RESUME_SESSION = "resumeSession";
    public static final String METHOD_FORK_SESSION = "forkSession";
    public static final String METHOD_LIST_SESSIONS = "listSessions";
    public static final String METHOD_CANCEL = "cancel";
    public static final String METHOD_PROMPT = "prompt";
    public static final String METHOD_SET_SESSION_MODEL = "setSessionModel";
    public static final String METHOD_SET_SESSION_MODE = "setSessionMode";
    public static final String METHOD_SET_CONFIG_OPTION = "setConfigOption";
    
    // ---- Transport -----------------------------------------------------------
    
    public static final String TRANSPORT_STDIO = "stdio";
    public static final String TRANSPORT_HTTP = "http";
    public static final String TRANSPORT_WEBSOCKET = "websocket";
    
    // ---- Tool kinds ----------------------------------------------------------
    
    public static final String TOOL_KIND_READ = "read";
    public static final String TOOL_KIND_EDIT = "edit";
    public static final String TOOL_KIND_SEARCH = "search";
    public static final String TOOL_KIND_EXECUTE = "execute";
    public static final String TOOL_KIND_FETCH = "fetch";
    public static final String TOOL_KIND_THINK = "think";
    public static final String TOOL_KIND_OTHER = "other";
    
    // ---- Stop reasons --------------------------------------------------------
    
    public static final String STOP_END_TURN = "end_turn";
    public static final String STOP_CANCELLED = "cancelled";
    public static final String STOP_REFUSAL = "refusal";
    public static final String STOP_ERROR = "error";
    public static final String STOP_RATE_LIMIT = "rate_limit";
    
    // ---- Content block types -------------------------------------------------
    
    public static final String BLOCK_TEXT = "text";
    public static final String BLOCK_IMAGE = "image";
    public static final String BLOCK_AUDIO = "audio";
    public static final String BLOCK_RESOURCE = "resource";
    public static final String BLOCK_EMBEDDED_RESOURCE = "embedded_resource";
    public static final String BLOCK_TOOL_USE = "tool_use";
    public static final String BLOCK_TOOL_RESULT = "tool_result";
    public static final String BLOCK_PAUSE = "pause";
    
    // ---- Session update types ------------------------------------------------
    
    public static final String UPDATE_AGENT_MESSAGE_TEXT = "agent.message.text";
    public static final String UPDATE_AGENT_THOUGHT_TEXT = "agent.thought.text";
    public static final String UPDATE_TOOL_CALL_START = "tool.call.start";
    public static final String UPDATE_TOOL_CALL_PROGRESS = "tool.call.progress";
    public static final String UPDATE_TOOL_CALL_END = "tool.call.end";
    public static final String UPDATE_AVAILABLE_COMMANDS = "availableCommands";
    
    // ---- Capability flags ----------------------------------------------------
    
    public static final String CAP_LOAD_SESSION = "loadSession";
    public static final String CAP_FORK = "fork";
    public static final String CAP_LIST = "list";
    public static final String CAP_RESUME = "resume";
    public static final String CAP_STREAM = "stream";
    
    // ---- Slash commands ------------------------------------------------------
    
    public static final String CMD_HELP = "help";
    public static final String CMD_MODEL = "model";
    public static final String CMD_TOOLS = "tools";
    public static final String CMD_CONTEXT = "context";
    public static final String CMD_RESET = "reset";
    public static final String CMD_COMPACT = "compact";
    public static final String CMD_VERSION = "version";
    
    // Built-in command names (without slash)
    public static final String[] SLASH_COMMANDS = {
        CMD_HELP, CMD_MODEL, CMD_TOOLS, CMD_CONTEXT, CMD_RESET, CMD_COMPACT, CMD_VERSION
    };
    
    private ACPProtocol() {}
}