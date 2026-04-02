package com.opszen.heal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class AuditLoggerTest {

    private AuditLogger auditLogger;

    @BeforeEach
    void setUp() {
        auditLogger = new AuditLogger();
    }

    @Test
    void should_mask_password_in_diagnosis() {
        String input = "Config error: password=SuperSecret123 in application.yml";
        String result = auditLogger.sanitize(input);
        assertFalse(result.contains("SuperSecret123"));
        assertTrue(result.contains("***REDACTED***"));
    }

    @Test
    void should_mask_api_key() {
        String input = "Found api-key=sk-abc123xyz in env";
        String result = auditLogger.sanitize(input);
        assertFalse(result.contains("sk-abc123xyz"));
    }

    @Test
    void should_mask_bearer_token() {
        String input = "Header: Bearer eyJhbGciOiJIUzI1NiJ9.payload.sig";
        String result = auditLogger.sanitize(input);
        assertFalse(result.contains("eyJhbGciOiJIUzI1NiJ9"));
    }

    @Test
    void should_mask_jdbc_url() {
        String input = "Connection: jdbc:mysql://user:pass@host:3306/db";
        String result = auditLogger.sanitize(input);
        assertFalse(result.contains("user:pass@host"));
    }

    @Test
    void should_mask_aws_access_key() {
        String input = "AWS key: AKIAIOSFODNN7EXAMPLE";
        String result = auditLogger.sanitize(input);
        assertFalse(result.contains("AKIAIOSFODNN7EXAMPLE"));
    }

    @Test
    void should_return_null_for_null_input() {
        assertNull(auditLogger.sanitize(null));
    }

    @Test
    void should_return_blank_for_blank_input() {
        assertEquals("  ", auditLogger.sanitize("  "));
    }

    @Test
    void should_not_alter_safe_text() {
        String safe = "Build failed: missing dependency spring-boot-starter-web";
        assertEquals(safe, auditLogger.sanitize(safe));
    }

    @Test
    void should_mask_secret_in_code_snapshot() {
        String code = "String secret = getSecret();\ntoken=abc123def";
        String result = auditLogger.sanitize(code);
        assertFalse(result.contains("abc123def"));
    }

    @Test
    void should_write_and_query_audit_entry() {
        // Uses default .opszen/audit dir - integration-style
        AuditLogger.AuditEntry entry = new AuditLogger.AuditEntry(
                "2026-04-02T10:00:00Z", "propose-only", "test",
                "password=secret123", "fix prompt", "code snapshot",
                "proposed", 100L
        );
        String filename = auditLogger.logEntry(entry);
        assertNotNull(filename);
        assertTrue(filename.endsWith(".json"));

        var history = auditLogger.queryHistory(1);
        assertFalse(history.isEmpty());
        // Verify the written entry has masked content
        String diagnosis = (String) history.get(0).get("diagnosis");
        assertFalse(diagnosis.contains("secret123"), "Sensitive data should be masked in audit log");
    }
}
