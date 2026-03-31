package com.vibeops.mcp;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * Core MCP Protocol data types following the Model Context Protocol spec.
 */
public final class McpProtocol {

    private McpProtocol() {}

    // ── JSON-RPC Envelope ──

    public record JsonRpcRequest(
            String jsonrpc,
            String method,
            @JsonInclude(JsonInclude.Include.NON_NULL) Object params,
            @JsonInclude(JsonInclude.Include.NON_NULL) Object id
    ) {
        public JsonRpcRequest(String method, Object params, Object id) {
            this("2.0", method, params, id);
        }
    }

    public record JsonRpcResponse(
            String jsonrpc,
            @JsonInclude(JsonInclude.Include.NON_NULL) Object result,
            @JsonInclude(JsonInclude.Include.NON_NULL) JsonRpcError error,
            Object id
    ) {
        public static JsonRpcResponse success(Object result, Object id) {
            return new JsonRpcResponse("2.0", result, null, id);
        }

        public static JsonRpcResponse error(int code, String message, Object id) {
            return new JsonRpcResponse("2.0", null, new JsonRpcError(code, message), id);
        }
    }

    public record JsonRpcError(int code, String message) {}

    // ── MCP Initialization ──

    public record ServerInfo(String name, String version) {}

    public record ServerCapabilities(ToolsCapability tools) {}

    public record ToolsCapability(@JsonProperty("listChanged") boolean listChanged) {}

    public record InitializeResult(
            String protocolVersion,
            ServerCapabilities capabilities,
            ServerInfo serverInfo
    ) {}

    // ── MCP Tool Definitions ──

    public record ToolDefinition(
            String name,
            String description,
            InputSchema inputSchema
    ) {}

    public record InputSchema(
            String type,
            Map<String, PropertyDef> properties,
            List<String> required
    ) {}

    public record PropertyDef(
            String type,
            String description,
            @JsonInclude(JsonInclude.Include.NON_NULL) List<String> items
    ) {}

    public record ToolListResult(List<ToolDefinition> tools) {}

    // ── MCP Tool Invocation ──

    public record ToolCallParams(String name, Map<String, Object> arguments) {}

    public record ToolResult(List<ContentBlock> content, boolean isError) {
        public static ToolResult success(String text) {
            return new ToolResult(List.of(new ContentBlock("text", text)), false);
        }

        public static ToolResult error(String text) {
            return new ToolResult(List.of(new ContentBlock("text", text)), true);
        }
    }

    public record ContentBlock(String type, String text) {}
}
