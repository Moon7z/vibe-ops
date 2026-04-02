package com.vibeops.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * HTTP transport for the MCP Server (Streamable HTTP).
 * Handles JSON-RPC requests for initialize, tools/list, and tools/call.
 */
@RestController
@RequestMapping("/mcp")
public class McpServerController {

    private static final Logger log = LoggerFactory.getLogger(McpServerController.class);

    private final McpToolRegistry registry;
    private final ObjectMapper objectMapper;
    private final VibeOpsMetrics metrics;

    @Value("${vibeops.mcp.version}")
    private String protocolVersion;

    @Value("${vibeops.mcp.server-name}")
    private String serverName;

    @Value("${vibeops.mcp.server-version}")
    private String serverVersion;

    public McpServerController(McpToolRegistry registry, ObjectMapper objectMapper, VibeOpsMetrics metrics) {
        this.registry = registry;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> status() {
        var tools = registry.listTools();
        return ResponseEntity.ok(Map.of(
                "status", "running",
                "server", serverName,
                "version", serverVersion,
                "protocol", protocolVersion,
                "tools", tools.stream().map(t -> Map.of(
                        "name", t.name(),
                        "description", t.description()
                )).toList()
        ));
    }

    @PostMapping
    public ResponseEntity<McpProtocol.JsonRpcResponse> handleRpc(
            @RequestBody McpProtocol.JsonRpcRequest request,
            HttpServletRequest httpRequest) {

        log.info("MCP request: method={}, id={}", request.method(), request.id());
        Timer.Sample timer = metrics.startTimer();

        ResponseEntity<McpProtocol.JsonRpcResponse> response = switch (request.method()) {
            case "initialize" -> handleInitialize(request);
            case "tools/list" -> handleToolsList(request);
            case "tools/call" -> handleToolCall(request, httpRequest);
            default -> ResponseEntity.ok(
                    McpProtocol.JsonRpcResponse.error(-32601, "Method not found: " + request.method(), request.id())
            );
        };

        String status = response.getBody() != null && response.getBody().error() == null ? "ok" : "error";
        metrics.recordMcpRequest(request.method(), status);
        metrics.stopTimer(timer, request.method());

        return response;
    }

    private ResponseEntity<McpProtocol.JsonRpcResponse> handleInitialize(McpProtocol.JsonRpcRequest request) {
        var result = new McpProtocol.InitializeResult(
                protocolVersion,
                new McpProtocol.ServerCapabilities(new McpProtocol.ToolsCapability(false)),
                new McpProtocol.ServerInfo(serverName, serverVersion)
        );
        return ResponseEntity.ok(McpProtocol.JsonRpcResponse.success(result, request.id()));
    }

    private ResponseEntity<McpProtocol.JsonRpcResponse> handleToolsList(McpProtocol.JsonRpcRequest request) {
        var result = new McpProtocol.ToolListResult(registry.listTools());
        return ResponseEntity.ok(McpProtocol.JsonRpcResponse.success(result, request.id()));
    }

    private ResponseEntity<McpProtocol.JsonRpcResponse> handleToolCall(
            McpProtocol.JsonRpcRequest request, HttpServletRequest httpRequest) {
        try {
            var params = objectMapper.convertValue(request.params(), McpProtocol.ToolCallParams.class);
            McpTool tool = registry.getTool(params.name());

            if (tool == null) {
                return ResponseEntity.ok(McpProtocol.JsonRpcResponse.success(
                        McpProtocol.ToolResult.error("Unknown tool: " + params.name()), request.id()));
            }

            Map<String, Object> args = params.arguments() != null
                    ? new java.util.HashMap<>(params.arguments()) : new java.util.HashMap<>();

            // Inject X-Vibeops-Env header if not already set in args
            String envHeader = httpRequest.getHeader("X-Vibeops-Env");
            if (envHeader != null && !envHeader.isBlank() && !args.containsKey("env")) {
                args.put("env", envHeader);
            }

            McpProtocol.ToolResult result = tool.execute(args);
            return ResponseEntity.ok(McpProtocol.JsonRpcResponse.success(result, request.id()));

        } catch (Exception e) {
            log.error("Tool execution failed", e);
            return ResponseEntity.ok(McpProtocol.JsonRpcResponse.success(
                    McpProtocol.ToolResult.error("Execution error: " + e.getMessage()), request.id()));
        }
    }
}
