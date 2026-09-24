package io.github.tuskworks.clans.lang;

import io.github.tuskworks.clans.service.Outcome;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * MiniMessage-based messages. Player-supplied values (names, tags) are always inserted
 * unparsed so they can't inject formatting or click events.
 */
public final class Messages {

    private static final List<String> BUNDLED = List.of("en", "zh_TW");

    private final JavaPlugin plugin;
    private final MiniMessage mini = MiniMessage.miniMessage();
    private volatile YamlConfiguration active = new YamlConfiguration();

    public Messages(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void load(String language) {
        for (String bundled : BUNDLED) {
            if (!new File(plugin.getDataFolder(), "lang/" + bundled + ".yml").exists()) {
                plugin.saveResource("lang/" + bundled + ".yml", false);
            }
        }
        YamlConfiguration fallback = new YamlConfiguration();
        try (InputStream in = plugin.getResource("lang/en.yml")) {
            if (in != null) {
                fallback = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
        } catch (java.io.IOException e) {
            plugin.getLogger().warning("Could not read bundled messages: " + e.getMessage());
        }
        File file = new File(plugin.getDataFolder(), "lang/" + language + ".yml");
        if (!file.exists()) {
            plugin.getLogger().warning("Language file lang/" + language + ".yml not found, using English");
            file = new File(plugin.getDataFolder(), "lang/en.yml");
        }
        YamlConfiguration loaded = YamlConfiguration.loadConfiguration(file);
        loaded.setDefaults(fallback);
        active = loaded;
    }

    public String raw(String key) {
        String value = active.getString(key);
        return value != null ? value : "<red>Missing message: " + key;
    }

    public Component render(String key, Map<String, String> vars, TagResolver... extra) {
        return mini.deserialize(raw(key), resolver(vars, extra));
    }

    public Component renderRaw(String miniMessage, Map<String, String> vars, TagResolver... extra) {
        return mini.deserialize(miniMessage, resolver(vars, extra));
    }

    public List<Component> renderList(String key, Map<String, String> vars) {
        List<Component> lines = new ArrayList<>();
        for (String line : active.getStringList(key)) {
            lines.add(mini.deserialize(line, resolver(vars)));
        }
        return lines;
    }

    public void send(Audience audience, String key, Map<String, String> vars, TagResolver... extra) {
        if (active.isList(key)) {
            renderList(key, vars).forEach(audience::sendMessage);
            return;
        }
        String raw = raw(key);
        if (!raw.isEmpty()) {
            audience.sendMessage(mini.deserialize(raw, resolver(vars, extra)));
        }
    }

    public void send(Audience audience, String key, String... kv) {
        send(audience, key, Outcome.vars(kv));
    }

    public void send(Audience audience, Outcome outcome) {
        send(audience, outcome.key(), outcome.vars());
    }

    private TagResolver resolver(Map<String, String> vars, TagResolver... extra) {
        TagResolver.Builder builder = TagResolver.builder();
        builder.resolver(Placeholder.parsed("prefix", active.getString("prefix", "")));
        vars.forEach((k, v) -> builder.resolver(Placeholder.unparsed(k, v)));
        builder.resolvers(extra);
        return builder.build();
    }
}
