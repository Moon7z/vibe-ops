package com.opszen.mcp;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central registry for all MCP tools.
 * Tools auto-register via Spring DI — adding a new tool only requires implementing McpTool.
 */
@Component
public class McpToolRegistry {

    private final Map<String, McpTool> tools = new ConcurrentHashMap<>();

    public McpToolRegistry(List<McpTool> toolBeans) {
        for (McpTool tool : toolBeans) {
            tools.put(tool.name(), tool);
        }
    }

    public McpTool getTool(String name) {
        return tools.get(name);
    }

    public List<McpProtocol.ToolDefinition> listTools() {
        return tools.values().stream()
                .map(t -> new McpProtocol.ToolDefinition(t.name(), t.description(), t.inputSchema()))
                .toList();
    }
}
