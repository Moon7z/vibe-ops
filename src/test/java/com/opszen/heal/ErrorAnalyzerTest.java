package com.opszen.heal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ErrorAnalyzer Tests")
class ErrorAnalyzerTest {

    private final ErrorAnalyzer analyzer = new ErrorAnalyzer();

    @Test
    @DisplayName("should detect OutOfMemoryError")
    void should_detectOOM() {
        String logs = """
                2024-01-15 10:30:00 ERROR [main] Application startup failed
                java.lang.OutOfMemoryError: Java heap space
                    at java.util.Arrays.copyOf(Arrays.java:3236)
                    at com.example.DataLoader.loadAll(DataLoader.java:42)
                """;

        var result = analyzer.analyze(logs);

        assertFalse(result.errors().isEmpty());
        assertEquals("OOM", result.errors().get(0).pattern().id());
        assertEquals(ErrorAnalyzer.Severity.CRITICAL, result.severity());
    }

    @Test
    @DisplayName("should detect database connection failure")
    void should_detectDBConnection() {
        String logs = """
                org.springframework.jdbc.CannotGetJdbcConnectionException:
                Failed to obtain JDBC Connection; nested exception is
                Communications link failure
                """;

        var result = analyzer.analyze(logs);

        assertTrue(result.errors().stream().anyMatch(e -> e.pattern().id().equals("DB_CONNECTION")));
        assertEquals(ErrorAnalyzer.Severity.HIGH, result.severity());
    }

    @Test
    @DisplayName("should detect CrashLoopBackOff")
    void should_detectCrashLoop() {
        String logs = "Warning  BackOff  pod/my-app-xyz  Back-off restarting failed container";

        var result = analyzer.analyze(logs);

        assertTrue(result.errors().stream().anyMatch(e -> e.pattern().id().equals("CRASH_LOOP")));
    }

    @Test
    @DisplayName("should detect NullPointerException")
    void should_detectNPE() {
        String logs = """
                Exception in thread "main" java.lang.NullPointerException
                    at com.example.Service.process(Service.java:55)
                    at com.example.Controller.handle(Controller.java:20)
                """;

        var result = analyzer.analyze(logs);

        assertTrue(result.errors().stream().anyMatch(e -> e.pattern().id().equals("NPE")));
        assertFalse(result.stackTraces().isEmpty());
    }

    @Test
    @DisplayName("should detect Spring bean creation failure")
    void should_detectBeanCreation() {
        String logs = """
                org.springframework.beans.factory.BeanCreationException:
                Error creating bean with name 'userService'
                """;

        var result = analyzer.analyze(logs);

        assertTrue(result.errors().stream().anyMatch(e -> e.pattern().id().equals("BEAN_CREATION")));
    }

    @Test
    @DisplayName("should detect missing configuration")
    void should_detectMissingConfig() {
        String logs = "Could not resolve placeholder 'DB_HOST' in value \"${DB_HOST}\"";

        var result = analyzer.analyze(logs);

        assertTrue(result.errors().stream().anyMatch(e -> e.pattern().id().equals("MISSING_CONFIG")));
    }

    @Test
    @DisplayName("should detect image pull failure")
    void should_detectImagePull() {
        String logs = "Failed to pull image \"myrepo/myapp:v2\": ImagePullBackOff";

        var result = analyzer.analyze(logs);

        assertTrue(result.errors().stream().anyMatch(e -> e.pattern().id().equals("IMAGE_PULL")));
    }

    @Test
    @DisplayName("should detect multiple errors")
    void should_detectMultipleErrors() {
        String logs = """
                java.lang.NullPointerException at com.example.Foo.bar(Foo.java:10)
                Communications link failure
                Could not resolve placeholder 'API_KEY'
                """;

        var result = analyzer.analyze(logs);

        assertEquals(3, result.errors().size());
    }

    @Test
    @DisplayName("should return empty for clean logs")
    void should_returnEmpty_forCleanLogs() {
        String logs = """
                2024-01-15 10:30:00 INFO Application started successfully
                2024-01-15 10:30:01 INFO Listening on port 8080
                """;

        var result = analyzer.analyze(logs);

        assertTrue(result.errors().isEmpty());
        assertEquals(ErrorAnalyzer.Severity.UNKNOWN, result.severity());
    }

    @Test
    @DisplayName("should handle null and blank input")
    void should_handleNullInput() {
        var nullResult = analyzer.analyze(null);
        assertTrue(nullResult.errors().isEmpty());

        var blankResult = analyzer.analyze("");
        assertTrue(blankResult.errors().isEmpty());
    }

    @Test
    @DisplayName("should build repair prompt")
    void should_buildRepairPrompt() {
        String logs = "java.lang.OutOfMemoryError: Java heap space";
        var result = analyzer.analyze(logs);
        String prompt = analyzer.buildRepairPrompt(result, "Spring Boot app on K8s");

        assertTrue(prompt.contains("OOM"));
        assertTrue(prompt.contains("root cause"));
        assertTrue(prompt.contains("Spring Boot app on K8s"));
    }
}
