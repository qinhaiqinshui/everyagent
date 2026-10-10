package dev.everyagent.worker.plugin.loader;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PluginDescriptorParser} 单元测试。
 */
class PluginDescriptorParserTest {

    @TempDir
    Path tempDir;

    // ---- 场景 1:正常解析(完整字段)----

    @Test
    void parseFullDescriptor() throws IOException {
        String json = """
                {
                  "id": "sandbox-docker",
                  "name": "Docker Sandbox",
                  "version": "1.0.0",
                  "description": "Run AI commands in Docker containers",
                  "author": "community",
                  "minAppVersion": "0.2.0",
                  "requires": {
                    "spi": ["SandboxProvider"],
                    "every-agent": ">=0.2.0"
                  },
                  "provides": {
                    "spi": {
                      "SandboxProvider": "io.example.DockerSandboxProvider"
                    },
                    "rpc": ["git.status", "git.log"],
                    "slash": ["git-auto-sync"],
                    "web": "web/index.js"
                  },
                  "activationEvents": ["onStartup"],
                  "contributes": {
                    "config": {
                      "sandbox-docker.image": {
                        "type": "string",
                        "default": "ubuntu:24.04",
                        "description": "Docker image"
                      }
                    }
                  }
                }
                """;

        PluginDescriptor d = PluginDescriptorParser.parse(json);

        assertEquals("sandbox-docker", d.id());
        assertEquals("Docker Sandbox", d.name());
        assertEquals("1.0.0", d.version());
        assertEquals("Run AI commands in Docker containers", d.description());
        assertEquals("community", d.author());
        assertEquals("0.2.0", d.minAppVersion());

        // requires
        assertNotNull(d.requires());
        assertEquals(List.of("SandboxProvider"), d.requires().spi());
        assertEquals(">=0.2.0", d.requires().everyAgent());

        // provides
        assertNotNull(d.provides());
        assertEquals(Map.of("SandboxProvider", "io.example.DockerSandboxProvider"), d.provides().spi());
        assertEquals(List.of("git.status", "git.log"), d.provides().rpc());
        assertEquals(List.of("git-auto-sync"), d.provides().slash());
        assertEquals("web/index.js", d.provides().web());

        // activationEvents
        assertEquals(List.of("onStartup"), d.activationEvents());

        // contributes
        assertNotNull(d.contributes());
        assertTrue(d.contributes().config().containsKey("sandbox-docker.image"));
        PluginDescriptor.ConfigItem item = d.contributes().config().get("sandbox-docker.image");
        assertEquals("string", item.type());
        assertEquals("ubuntu:24.04", item.defaultValue());
        assertEquals("Docker image", item.description());
    }

    // ---- 场景 2:缺失可选字段(无 requires / provides / contributes)----

    @Test
    void parseMissingOptionalFields() throws IOException {
        String json = """
                {
                  "id": "minimal-plugin",
                  "name": "Minimal",
                  "version": "0.1.0"
                }
                """;

        PluginDescriptor d = PluginDescriptorParser.parse(json);

        assertEquals("minimal-plugin", d.id());
        assertEquals("Minimal", d.name());
        assertEquals("0.1.0", d.version());
        assertEquals("", d.description());
        assertEquals("", d.author());
        assertEquals("", d.minAppVersion());

        // requires → 空默认
        assertNotNull(d.requires());
        assertTrue(d.requires().spi().isEmpty());
        assertEquals("", d.requires().everyAgent());

        // provides → 空默认
        assertNotNull(d.provides());
        assertTrue(d.provides().spi().isEmpty());
        assertTrue(d.provides().rpc().isEmpty());
        assertTrue(d.provides().slash().isEmpty());
        assertEquals("", d.provides().web());

        // activationEvents → 空 list
        assertNotNull(d.activationEvents());
        assertTrue(d.activationEvents().isEmpty());

        // contributes → 空 map
        assertNotNull(d.contributes());
        assertTrue(d.contributes().config().isEmpty());
    }

    // ---- 场景 3:空 JSON 对象 ----

    @Test
    void parseEmptyObject() throws IOException {
        String json = "{}";

        PluginDescriptor d = PluginDescriptorParser.parse(json);

        assertEquals("", d.id());
        assertEquals("", d.name());
        assertEquals("", d.version());
        assertEquals("", d.description());
        assertEquals("", d.author());
        assertEquals("", d.minAppVersion());

        assertNotNull(d.requires());
        assertTrue(d.requires().spi().isEmpty());
        assertEquals("", d.requires().everyAgent());

        assertNotNull(d.provides());
        assertTrue(d.provides().spi().isEmpty());
        assertTrue(d.provides().rpc().isEmpty());
        assertTrue(d.provides().slash().isEmpty());
        assertEquals("", d.provides().web());

        assertNotNull(d.activationEvents());
        assertTrue(d.activationEvents().isEmpty());

        assertNotNull(d.contributes());
        assertTrue(d.contributes().config().isEmpty());
    }

    // ---- 场景 4:坏 JSON 抛异常 ----

    @Test
    void parseBadJsonThrows() {
        String badJson = "{ this is not valid json ]";

        assertThrows(IOException.class, () -> PluginDescriptorParser.parse(badJson));
    }

    @Test
    void parseNonObjectRootThrows() {
        String jsonArray = "[1, 2, 3]";
        assertThrows(IOException.class, () -> PluginDescriptorParser.parse(jsonArray));
    }

    // ---- parseFile ----

    @Test
    void parseFileReadsAndParses() throws IOException {
        String json = """
                {
                  "id": "file-plugin",
                  "name": "File Plugin",
                  "version": "2.0.0"
                }
                """;
        Path file = tempDir.resolve("plugin.json");
        Files.writeString(file, json);

        PluginDescriptor d = PluginDescriptorParser.parseFile(file);

        assertEquals("file-plugin", d.id());
        assertEquals("File Plugin", d.name());
        assertEquals("2.0.0", d.version());
    }

    @Test
    void parseFileMissingFileThrows() {
        Path missing = tempDir.resolve("nonexistent-plugin.json");
        assertThrows(IOException.class, () -> PluginDescriptorParser.parseFile(missing));
    }

    // ---- ConfigItem.defaultValue 类型保留 ----

    @Test
    void configItemDefaultValuePreservesType() throws IOException {
        String json = """
                {
                  "id": "typed",
                  "contributes": {
                    "config": {
                      "typed.int": { "type": "number", "default": 42, "description": "an int" },
                      "typed.bool": { "type": "boolean", "default": true, "description": "a bool" },
                      "typed.string": { "type": "string", "default": "hello", "description": "a string" },
                      "typed.nested": { "type": "object", "default": { "a": 1 }, "description": "an object" }
                    }
                  }
                }
                """;

        PluginDescriptor d = PluginDescriptorParser.parse(json);

        Map<String, PluginDescriptor.ConfigItem> config = d.contributes().config();
        assertEquals(42L, config.get("typed.int").defaultValue());
        assertEquals(Boolean.TRUE, config.get("typed.bool").defaultValue());
        assertEquals("hello", config.get("typed.string").defaultValue());
        // 嵌套对象保留为 JsonNode(非 null 即可)
        assertNotNull(config.get("typed.nested").defaultValue());
    }

    @Test
    void configItemWithoutDefaultHasNullDefaultValue() throws IOException {
        String json = """
                {
                  "id": "no-default",
                  "contributes": {
                    "config": {
                      "no.default": { "type": "string", "description": "no default value" }
                    }
                  }
                }
                """;

        PluginDescriptor d = PluginDescriptorParser.parse(json);

        PluginDescriptor.ConfigItem item = d.contributes().config().get("no.default");
        assertEquals("string", item.type());
        assertEquals("no default value", item.description());
        assertNull(item.defaultValue());
    }
}
