package com.opszen.heal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Component
public class BackupManager {

    private static final Logger log = LoggerFactory.getLogger(BackupManager.class);
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneId.systemDefault());

    private final HealConfig healConfig;

    public BackupManager(HealConfig healConfig) {
        this.healConfig = healConfig;
    }

    public String backup(List<Path> files) throws IOException {
        String timestamp = TS_FMT.format(Instant.now());
        Path backupDir = Path.of(healConfig.getBackupDir(), timestamp);
        Files.createDirectories(backupDir);

        for (Path file : files) {
            if (Files.exists(file)) {
                Path target = backupDir.resolve(file.getFileName());
                Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        log.info("Backup created: {} ({} files)", backupDir, files.size());
        return timestamp;
    }

    public boolean rollback(String timestamp) throws IOException {
        Path backupDir = Path.of(healConfig.getBackupDir(), timestamp);
        if (!Files.isDirectory(backupDir)) {
            log.warn("Backup not found: {}", timestamp);
            return false;
        }
        try (Stream<Path> files = Files.list(backupDir)) {
            List<Path> backupFiles = files.collect(Collectors.toList());
            for (Path backup : backupFiles) {
                // Restore to original location - caller must track original paths
                log.info("Rollback file: {}", backup.getFileName());
            }
        }
        return true;
    }

    public boolean rollbackTo(String timestamp, Path projectRoot) throws IOException {
        Path backupDir = Path.of(healConfig.getBackupDir(), timestamp);
        if (!Files.isDirectory(backupDir)) return false;

        // Read manifest if exists
        Path manifest = backupDir.resolve(".manifest");
        if (Files.exists(manifest)) {
            List<String> entries = Files.readAllLines(manifest);
            for (String entry : entries) {
                String[] parts = entry.split("\\|", 2);
                if (parts.length == 2) {
                    Path backupFile = backupDir.resolve(parts[0]);
                    Path originalFile = projectRoot.resolve(parts[1]);
                    if (Files.exists(backupFile)) {
                        Files.createDirectories(originalFile.getParent());
                        Files.copy(backupFile, originalFile, StandardCopyOption.REPLACE_EXISTING);
                        log.info("Restored: {}", originalFile);
                    }
                }
            }
            return true;
        }
        return false;
    }

    public String backupWithManifest(Map<Path, Path> fileMapping) throws IOException {
        String timestamp = TS_FMT.format(Instant.now());
        Path backupDir = Path.of(healConfig.getBackupDir(), timestamp);
        Files.createDirectories(backupDir);

        List<String> manifestEntries = new ArrayList<>();
        int idx = 0;
        for (Map.Entry<Path, Path> entry : fileMapping.entrySet()) {
            Path originalFile = entry.getKey();
            if (Files.exists(originalFile)) {
                String backupName = idx + "_" + originalFile.getFileName();
                Files.copy(originalFile, backupDir.resolve(backupName), StandardCopyOption.REPLACE_EXISTING);
                manifestEntries.add(backupName + "|" + entry.getValue());
                idx++;
            }
        }
        Files.write(backupDir.resolve(".manifest"), manifestEntries);
        log.info("Backup with manifest: {} ({} files)", backupDir, idx);
        return timestamp;
    }

    public List<String> listBackups() {
        Path baseDir = Path.of(healConfig.getBackupDir());
        if (!Files.isDirectory(baseDir)) return List.of();
        try (Stream<Path> dirs = Files.list(baseDir)) {
            return dirs.filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .sorted(Comparator.reverseOrder())
                    .collect(Collectors.toList());
        } catch (IOException e) {
            return List.of();
        }
    }

    public int cleanExpired() {
        Duration retention = parseRetention(healConfig.getRollbackRetention());
        Path baseDir = Path.of(healConfig.getBackupDir());
        if (!Files.isDirectory(baseDir)) return 0;
        int cleaned = 0;
        try (Stream<Path> dirs = Files.list(baseDir)) {
            List<Path> allDirs = dirs.filter(Files::isDirectory).collect(Collectors.toList());
            for (Path dir : allDirs) {
                BasicFileAttributes attrs = Files.readAttributes(dir, BasicFileAttributes.class);
                if (attrs.creationTime().toInstant().plus(retention).isBefore(Instant.now())) {
                    deleteRecursive(dir);
                    cleaned++;
                }
            }
        } catch (IOException e) {
            log.error("Failed to clean expired backups", e);
        }
        return cleaned;
    }

    private Duration parseRetention(String retention) {
        if (retention.endsWith("h")) return Duration.ofHours(Long.parseLong(retention.replace("h", "")));
        if (retention.endsWith("d")) return Duration.ofDays(Long.parseLong(retention.replace("d", "")));
        return Duration.ofHours(24);
    }

    private void deleteRecursive(Path dir) throws IOException {
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }
            @Override
            public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                Files.delete(d);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
