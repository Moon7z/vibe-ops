package com.opszen.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("CodeDiffReader Tests")
class CodeDiffReaderTest {

    private final CodeDiffReader reader = new CodeDiffReader();

    @Test
    @DisplayName("should read a single file")
    void should_readSingleFile(@TempDir Path tempDir) throws IOException {
        Path javaFile = tempDir.resolve("Foo.java");
        Files.writeString(javaFile, "public class Foo {}");

        String content = reader.readFile(javaFile.toString());
        assertEquals("public class Foo {}", content);
    }

    @Test
    @DisplayName("should throw on missing file")
    void should_throw_on_missingFile() {
        assertThrows(IOException.class, () ->
                reader.readFile("/nonexistent/path/Foo.java"));
    }

    @Test
    @DisplayName("should scan all Java files when no git changes")
    void should_scanAllFiles_when_noGitChanges(@TempDir Path tempDir) throws IOException {
        // Create fake project structure
        Path srcMain = tempDir.resolve("src/main/java/com/example");
        Files.createDirectories(srcMain);
        Files.writeString(srcMain.resolve("App.java"), "package com.example;\npublic class App {}");
        Files.writeString(srcMain.resolve("Service.java"), "package com.example;\npublic class Service {}");

        // Also create a test file (should be excluded)
        Path srcTest = tempDir.resolve("src/test/java/com/example");
        Files.createDirectories(srcTest);
        Files.writeString(srcTest.resolve("AppTest.java"), "package com.example;\npublic class AppTest {}");

        Map<String, String> changed = reader.readChangedFiles(tempDir.toString());

        // Should include main sources but not test sources
        assertEquals(2, changed.size());
        assertTrue(changed.values().stream().noneMatch(v -> v.contains("AppTest")));
    }

    @Test
    @DisplayName("should throw on invalid project path")
    void should_throw_on_invalidProjectPath() {
        assertThrows(IOException.class, () ->
                reader.readChangedFiles("/nonexistent/project"));
    }
}
