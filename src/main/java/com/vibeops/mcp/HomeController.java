package com.vibeops.mcp;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Root endpoint — shows server status when accessing http://localhost:3100/
 */
@RestController
public class HomeController {

    private final McpToolRegistry registry;

    @Value("${vibeops.mcp.server-name}")
    private String serverName;

    @Value("${vibeops.mcp.server-version}")
    private String serverVersion;

    public HomeController(McpToolRegistry registry) {
        this.registry = registry;
    }

    @GetMapping("/")
    public ResponseEntity<Map<String, Object>> home() {
        var toolNames = registry.listTools().stream()
                .map(t -> Map.of(
                        "name", (Object) t.name(),
                        "description", (Object) t.description()
                ))
                .toList();

        return ResponseEntity.ok(Map.of(
                "status", "running",
                "server", serverName,
                "version", serverVersion,
                "endpoint", "POST /mcp",
                "tools_count", toolNames.size(),
                "tools", toolNames
        ));
    }
}
