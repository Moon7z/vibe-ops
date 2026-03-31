package com.vibeops.test;

import com.vibeops.mcp.McpProtocol;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("GenerateTestsTool Tests")
class GenerateTestsToolTest {

    private final CodeDiffReader diffReader = new CodeDiffReader();
    private final TestPromptEngine promptEngine = new TestPromptEngine();
    private final GenerateTestsTool tool = new GenerateTestsTool(diffReader, promptEngine);

    @Test
    @DisplayName("should have correct tool name")
    void should_haveCorrectName() {
        assertEquals("generate-tests", tool.name());
    }

    @Test
    @DisplayName("should require projectPath parameter")
    void should_errorOnMissingProjectPath() {
        var result = tool.execute(Map.of());
        assertTrue(result.isError());
    }

    @Test
    @DisplayName("should generate prompts for source files")
    void should_generatePrompts(@TempDir Path tempDir) throws IOException {
        // Set up a fake project
        Path srcMain = tempDir.resolve("src/main/java/com/example");
        Files.createDirectories(srcMain);
        Files.writeString(srcMain.resolve("Calculator.java"),
                "package com.example;\n\npublic class Calculator {\n" +
                "    public int add(int a, int b) { return a + b; }\n" +
                "}\n");

        var result = tool.execute(Map.of(
                "projectPath", tempDir.toString(),
                "mode", "prompt"
        ));

        assertFalse(result.isError());
        String text = result.content().get(0).text();
        assertTrue(text.contains("Calculator"));
        assertTrue(text.contains("AI Prompt"));
    }

    @Test
    @DisplayName("should generate stubs for source files")
    void should_generateStubs(@TempDir Path tempDir) throws IOException {
        Path srcMain = tempDir.resolve("src/main/java/com/example");
        Files.createDirectories(srcMain);
        Files.writeString(srcMain.resolve("UserService.java"),
                "package com.example;\n\nimport org.springframework.stereotype.Service;\n\n" +
                "@Service\npublic class UserService {\n" +
                "    public String getUser(String id) { return id; }\n" +
                "}\n");

        var result = tool.execute(Map.of(
                "projectPath", tempDir.toString(),
                "mode", "stub"
        ));

        assertFalse(result.isError());
        String text = result.content().get(0).text();
        assertTrue(text.contains("Created"));

        // Verify the stub file was actually written
        Path testFile = tempDir.resolve("src/test/java/com/example/UserServiceTest.java");
        assertTrue(Files.exists(testFile));
        String testContent = Files.readString(testFile);
        assertTrue(testContent.contains("UserServiceTest"));
        assertTrue(testContent.contains("@ExtendWith(MockitoExtension.class)"));
    }

    @Test
    @DisplayName("should not overwrite existing test files")
    void should_skipExistingTests(@TempDir Path tempDir) throws IOException {
        // Create source + existing test
        Path srcMain = tempDir.resolve("src/main/java/com/example");
        Path srcTest = tempDir.resolve("src/test/java/com/example");
        Files.createDirectories(srcMain);
        Files.createDirectories(srcTest);
        Files.writeString(srcMain.resolve("Foo.java"), "package com.example;\npublic class Foo {}");
        Files.writeString(srcTest.resolve("FooTest.java"), "// existing test");

        var result = tool.execute(Map.of(
                "projectPath", tempDir.toString(),
                "mode", "stub"
        ));

        assertFalse(result.isError());
        String text = result.content().get(0).text();
        assertTrue(text.contains("Skipped"));

        // Verify existing test was NOT overwritten
        assertEquals("// existing test", Files.readString(srcTest.resolve("FooTest.java")));
    }
}
