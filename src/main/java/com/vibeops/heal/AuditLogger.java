package com.vibeops.heal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Component
public class AuditLogger {

    private static final Logger log = LoggerFactory.getLogger(AuditLogger.class);
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
            .withZone(ZoneId.systemDefault());

    private static final List<Pattern> SENSITIVE_PATTERNS = List.of(
            Pattern.compile("(?i)(password|passwd|pwd)\\s*[=:]\\s*\\S+"),
            Pattern.compile("(?i)(api[_-]?key|apikey|access[_-]?key)\\s*[=:]\\s*\\S+"),
            Pattern.compile("(?i)(secret|token|bearer)\\s*[=:]\\s*\\S+"),
            Pattern.compile("(?i)Bearer\\s+[A-Za-z0-9\\-._~+/]+=*"),
            Pattern.compile("(?i)(jdbc|mysql|postgresql|mongodb|redis)://[^\\s\"']+"),
            Pattern.compile("(?i)(AKIA|ABIA|ACCA|ASIA)[A-Z0-9]{16}"),
            Pattern.compile("(?i)-----BEGIN\\s+(RSA\\s+)?PRIVATE\\s+KEY-----[\\s\\S]*?-----END")
    );
    private static final String MASK = "***REDACTED***";

    private final ObjectMapper mapper;

    public AuditLogger() {
        this.mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    }

    String sanitize(String input) {
        if (input == null || input.isBlank()) return input;
        String result = input;
        for (Pattern p : SENSITIVE_PATTERNS) {
            result = p.matcher(result).replaceAll(MASK);
        }
        return result;
    }

    private AuditEntry sanitize(AuditEntry entry) {
        return new AuditEntry(
                entry.timestamp(), entry.mode(), entry.trigger(),
                sanitize(entry.diagnosis()),
                sanitize(entry.fixPrompt()),
                sanitize(entry.fixCodeSnapshot()),
                entry.status(), entry.durationMs()
        );
    }

    public String logEntry(AuditEntry entry) {
        try {
            Path auditDir = Path.of(".vibeops/audit");
            Files.createDirectories(auditDir);
            String filename = TS_FMT.format(Instant.now()) + ".json";
            Path file = auditDir.resolve(filename);
            mapper.writeValue(file.toFile(), sanitize(entry));
            log.info("Audit log written: {}", file);
            return filename;
        } catch (IOException e) {
            log.error("Failed to write audit log", e);
            return null;
        }
    }

    public List<Map<String, Object>> queryHistory(int limit) {
        Path auditDir = Path.of(".vibeops/audit");
        if (!Files.isDirectory(auditDir)) return List.of();
        try (Stream<Path> files = Files.list(auditDir)) {
            return files
                    .filter(p -> p.toString().endsWith(".json"))
                    .sorted(Comparator.reverseOrder())
                    .limit(limit > 0 ? limit : 20)
                    .map(this::readEntry)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
        } catch (IOException e) {
            log.error("Failed to read audit history", e);
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readEntry(Path file) {
        try {
            return mapper.readValue(file.toFile(), Map.class);
        } catch (IOException e) {
            return null;
        }
    }

    public record AuditEntry(
            String timestamp,
            String mode,
            String trigger,
            String diagnosis,
            String fixPrompt,
            String fixCodeSnapshot,
            String status, // proposed | applied | rolled_back
            long durationMs
    ) {}
}
