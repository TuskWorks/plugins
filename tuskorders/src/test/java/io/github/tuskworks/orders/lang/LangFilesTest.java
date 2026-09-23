package io.github.tuskworks.orders.lang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class LangFilesTest {

    /** String literals in the code that look like message keys. */
    private static final Pattern KEY_LITERAL = Pattern.compile(
            "\"((?:error|help|reload|prompt|search|create|deliver|cancel|collect|notify|gui)\\.[a-z0-9.-]+)\"");

    private static YamlConfiguration load(String name) throws IOException {
        try (InputStream in = LangFilesTest.class.getResourceAsStream("/lang/" + name + ".yml")) {
            return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
    }

    private static Set<String> leafKeys(YamlConfiguration yaml) {
        Set<String> keys = new TreeSet<>();
        for (String key : yaml.getKeys(true)) {
            if (!yaml.isConfigurationSection(key)) {
                keys.add(key);
            }
        }
        return keys;
    }

    @Test
    void translationsHaveTheSameKeys() throws IOException {
        assertEquals(leafKeys(load("en")), leafKeys(load("zh_TW")));
    }

    @Test
    void everyKeyUsedInCodeExists() throws IOException {
        Set<String> keys = leafKeys(load("en"));
        Set<String> missing = new TreeSet<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                Matcher m = KEY_LITERAL.matcher(Files.readString(file));
                while (m.find()) {
                    String key = m.group(1);
                    // Prefixes completed at runtime, e.g. "gui.sort." + name
                    if (!key.endsWith(".") && !keys.contains(key)) {
                        missing.add(key + " (" + file.getFileName() + ")");
                    }
                }
            }
        }
        assertTrue(missing.isEmpty(), "Missing message keys: " + missing);
    }
}
