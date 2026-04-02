package com.opszen.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TestPromptEngine Tests")
class TestPromptEngineTest {

    private final TestPromptEngine engine = new TestPromptEngine();

    @Test
    @DisplayName("should extract class name from source code")
    void should_extractClassName_from_source() {
        String source = "package com.example;\n\npublic class MyService {\n}";
        assertEquals("MyService", engine.extractClassName(source));
    }

    @Test
    @DisplayName("should extract record name")
    void should_extractRecordName() {
        String source = "package com.example;\n\npublic record Config(String key) {}";
        assertEquals("Config", engine.extractClassName(source));
    }

    @Test
    @DisplayName("should extract package name")
    void should_extractPackage() {
        String source = "package com.opszen.mcp;\n\npublic class Foo {}";
        assertEquals("com.opszen.mcp", engine.extractPackage(source));
    }

    @Test
    @DisplayName("should generate prompt with controller hints")
    void should_includeControllerHints_when_restController() {
        String source = """
                package com.example;
                import org.springframework.web.bind.annotation.*;
                @RestController
                public class UserController {
                    @GetMapping("/users")
                    public List<User> getUsers() { return List.of(); }
                }
                """;
        String prompt = engine.buildTestGenerationPrompt(source, "UserController", "");
        assertTrue(prompt.contains("REST controller"));
        assertTrue(prompt.contains("HTTP methods"));
    }

    @Test
    @DisplayName("should generate integration test prompt for controllers")
    void should_generateIntegrationPrompt() {
        String source = "package com.example;\n@RestController\npublic class FooController {}";
        String prompt = engine.buildIntegrationTestPrompt(source, "FooController");
        assertTrue(prompt.contains("MockMvc"));
        assertTrue(prompt.contains("FooControllerIntegrationTest"));
    }

    @Test
    @DisplayName("should detect service bean for mock hints")
    void should_includeServiceHints_when_component() {
        String source = "package com.example;\n@Service\npublic class OrderService {}";
        String prompt = engine.buildTestGenerationPrompt(source, "OrderService", "");
        assertTrue(prompt.contains("Spring-managed bean"));
    }

    @Test
    @DisplayName("should include exception handling hints")
    void should_includeExceptionHints_when_throwsPresent() {
        String source = "package com.example;\npublic class Foo { void bar() throws IOException {} }";
        String prompt = engine.buildTestGenerationPrompt(source, "Foo", "");
        assertTrue(prompt.contains("error paths"));
    }
}
