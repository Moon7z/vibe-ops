package com.opszen;

import com.opszen.mcp.McpProtocol;
import com.opszen.mcp.McpToolRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class OpsZenApplicationTests {

    @Autowired
    private McpToolRegistry registry;

    @Test
    void contextLoads() {
        assertNotNull(registry);
    }

    @Test
    void allToolsRegistered() {
        var tools = registry.listTools();
        assertEquals(10, tools.size());

        var names = tools.stream().map(McpProtocol.ToolDefinition::name).toList();
        assertTrue(names.contains("analyze-vibe"));
        assertTrue(names.contains("run-vibe-check"));
        assertTrue(names.contains("generate-tests"));
        assertTrue(names.contains("run-tests"));
        assertTrue(names.contains("test-gate"));
        assertTrue(names.contains("generate-infra"));
        assertTrue(names.contains("diagnose-failure"));
        assertTrue(names.contains("auto-heal"));
        assertTrue(names.contains("audit-history"));
        assertTrue(names.contains("rollback"));
    }

    @Test
    void analyzeVibeReturnsReport() {
        var tool = registry.getTool("analyze-vibe");
        var result = tool.execute(java.util.Map.of(
                "prompt", "Build a REST API with JWT auth, retry logic, and unit tests"
        ));
        assertFalse(result.isError());
        String text = result.content().get(0).text();
        assertTrue(text.contains("Vibe Analysis Report"));
        assertTrue(text.contains("COVERED") || text.contains("PARTIAL"));
    }

    @Test
    void runVibeCheckScansFiles() {
        var tool = registry.getTool("run-vibe-check");
        // Scan the project's own source
        var result = tool.execute(java.util.Map.of(
                "path", System.getProperty("user.dir") + "/src/main/java"
        ));
        assertFalse(result.isError());
        String text = result.content().get(0).text();
        assertTrue(text.contains("Vibe-Check Scan Report"));
    }
}
