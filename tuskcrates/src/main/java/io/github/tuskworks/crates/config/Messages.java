package io.github.tuskworks.crates.config;

import io.github.tuskworks.crates.util.Text;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

public final class Messages {

    private final YamlConfiguration yaml;
    private final TagResolver prefix;

    private Messages(YamlConfiguration yaml) {
        this.yaml = yaml;
        this.prefix = Placeholder.parsed("prefix", yaml.getString("prefix", ""));
    }

    public static Messages load(JavaPlugin plugin) {
        File file = new File(plugin.getDataFolder(), "messages.yml");
        if (!file.exists()) {
            plugin.saveResource("messages.yml", false);
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        var bundled = plugin.getResource("messages.yml");
        if (bundled != null) {
            // Keys added in newer versions fall back to the bundled defaults.
            yaml.setDefaults(YamlConfiguration.loadConfiguration(
                    new InputStreamReader(bundled, StandardCharsets.UTF_8)));
        }
        return new Messages(yaml);
    }

    public String raw(String key) {
        return Objects.requireNonNullElse(yaml.getString(key), key);
    }

    public Component get(String key, TagResolver... resolvers) {
        return Text.parse(raw(key), withPrefix(resolvers));
    }

    /** Same as {@link #get} but without default italics, for item names and lore. */
    public Component item(String key, TagResolver... resolvers) {
        return Text.item(raw(key), withPrefix(resolvers));
    }

    public List<Component> itemLines(List<String> keys, TagResolver... resolvers) {
        return keys.stream().map(key -> item(key, resolvers)).toList();
    }

    public void send(Audience audience, String key, TagResolver... resolvers) {
        String raw = raw(key);
        if (!raw.isEmpty()) {
            audience.sendMessage(Text.parse(raw, withPrefix(resolvers)));
        }
    }

    private TagResolver withPrefix(TagResolver... resolvers) {
        return TagResolver.resolver(TagResolver.resolver(resolvers), prefix);
    }
}
