package com.vibeops.mcp;

import java.util.Map;

/**
 * Interface for all Vibe-Ops MCP tools.
 * Each module (Spec-Verifier, Test-Autopilot, etc.) exposes tools through this contract.
 */
public interface McpTool {

    /** Tool name as exposed via MCP protocol. */
    String name();

    /** Human-readable description for LLM consumption. */
    String description();

    /** JSON Schema definition for the tool's input parameters. */
    McpProtocol.InputSchema inputSchema();

    /** Execute the tool with the given arguments. Must be thread-safe. */
    McpProtocol.ToolResult execute(Map<String, Object> arguments);
}
