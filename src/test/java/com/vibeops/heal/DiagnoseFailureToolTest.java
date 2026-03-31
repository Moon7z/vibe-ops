package com.vibeops.heal;

import com.vibeops.mcp.McpProtocol;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("DiagnoseFailureTool Tests")
class DiagnoseFailureToolTest {

    private final ErrorAnalyzer analyzer = new ErrorAnalyzer();
    private final HealingStrategyEngine engine = new HealingStrategyEngine();
    private final DiagnoseFailureTool tool = new DiagnoseFailureTool(analyzer, engine);

    @Test
    @DisplayName("should have correct tool name")
    void should_haveCorrectName() {
        assertEquals("diagnose-failure", tool.name());
    }

    @Test
    @DisplayName("should error on missing logs")
    void should_errorOnMissingLogs() {
        var result = tool.execute(Map.of());
        assertTrue(result.isError());
    }

    @Test
    @DisplayName("should diagnose OOM with repair plan")
    void should_diagnoseOOM() {
        var result = tool.execute(Map.of(
                "logs", "java.lang.OutOfMemoryError: Java heap space\n" +
                        "    at com.example.App.main(App.java:10)"
        ));

        assertFalse(result.isError());
        String text = result.content().getFirst().text();
        assertTrue(text.contains("Failure Diagnosis Report"));
        assertTrue(text.contains("CRITICAL"));
        assertTrue(text.contains("OOM"));
        assertTrue(text.contains("Repair Plan"));
        assertTrue(text.contains("Increase memory limits"));
    }

    @Test
    @DisplayName("should diagnose multiple errors")
    void should_diagnoseMultipleErrors() {
        var result = tool.execute(Map.of(
                "logs", """
                        NullPointerException at com.example.Foo.bar(Foo.java:10)
                        Communications link failure to database host
                        Could not resolve placeholder 'SECRET_KEY'
                        """
        ));

        assertFalse(result.isError());
        String text = result.content().getFirst().text();
        assertTrue(text.contains("NPE"));
        assertTrue(text.contains("DB_CONNECTION"));
        assertTrue(text.contains("MISSING_CONFIG"));
    }

    @Test
    @DisplayName("should report no errors for clean logs")
    void should_reportClean() {
        var result = tool.execute(Map.of(
                "logs", "INFO Application started on port 8080"
        ));

        assertFalse(result.isError());
        String text = result.content().getFirst().text();
        assertTrue(text.contains("No Known Error Patterns"));
    }
}
