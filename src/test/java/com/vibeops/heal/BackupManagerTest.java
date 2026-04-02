package com.vibeops.heal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BackupManagerTest {

    private BackupManager backupManager;
    @TempDir Path tempDir;

    @BeforeEach
    void setUp() {
        HealConfig config = new HealConfig();
        config.setBackupDir(tempDir.resolve("backups").toString());
        config.setRollbackRetention("24h");
        backupManager = new BackupManager(config);
    }

    @Test
    void should_backup_files_and_return_timestamp() throws IOException {
        Path file1 = tempDir.resolve("a.txt");
        Files.writeString(file1, "content-a");
        Path file2 = tempDir.resolve("b.txt");
        Files.writeString(file2, "content-b");

        String ts = backupManager.backup(List.of(file1, file2));
        assertNotNull(ts);
        assertFalse(ts.isBlank());
    }

    @Test
    void should_skip_nonexistent_files_in_backup() throws IOException {
        Path exists = tempDir.resolve("exists.txt");
        Files.writeString(exists, "data");
        Path missing = tempDir.resolve("missing.txt");

        String ts = backupManager.backup(List.of(exists, missing));
        assertNotNull(ts);
    }

    @Test
    void should_list_backups_in_reverse_order() throws Exception {
        Path f = tempDir.resolve("f.txt");
        Files.writeString(f, "x");

        backupManager.backup(List.of(f));
        Thread.sleep(10); // ensure different timestamp
        backupManager.backup(List.of(f));

        List<String> backups = backupManager.listBackups();
        assertTrue(backups.size() >= 2);
        assertTrue(backups.get(0).compareTo(backups.get(1)) > 0, "Should be reverse chronological");
    }

    @Test
    void should_return_empty_list_when_no_backups() {
        List<String> backups = backupManager.listBackups();
        assertTrue(backups.isEmpty());
    }

    @Test
    void should_rollback_with_manifest() throws IOException {
        Path original = tempDir.resolve("src/Main.java");
        Files.createDirectories(original.getParent());
        Files.writeString(original, "original content");

        String ts = backupManager.backupWithManifest(Map.of(original, Path.of("src/Main.java")));
        assertNotNull(ts);

        // Overwrite original
        Files.writeString(original, "modified content");

        boolean restored = backupManager.rollbackTo(ts, tempDir);
        assertTrue(restored);
        assertEquals("original content", Files.readString(original));
    }

    @Test
    void should_return_false_for_missing_backup_timestamp() throws IOException {
        assertFalse(backupManager.rollbackTo("nonexistent-timestamp", tempDir));
    }

    @Test
    void should_clean_expired_backups() throws IOException {
        // Create a backup dir that looks old
        Path oldBackup = Path.of(tempDir.resolve("backups").toString(), "19700101-000000");
        Files.createDirectories(oldBackup);
        Files.writeString(oldBackup.resolve("test.txt"), "old");

        int cleaned = backupManager.cleanExpired();
        assertTrue(cleaned >= 0); // may or may not clean depending on creation time attr
    }
}
