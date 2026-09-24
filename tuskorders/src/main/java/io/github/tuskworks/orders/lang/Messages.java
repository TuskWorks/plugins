package io.github.tuskworks.orders.lang;

import io.github.tuskworks.orders.service.Outcome;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * MiniMessage-based messages. Player-supplied values (names, search text) are always inserted
 * unparsed so they can't inject formatting or click events. An {@code item_key} variable is
 * rendered as the item's translated name, so every client sees it in their own language.
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
        } catch (IOException e) {
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

    public Component render(String key, Map<String, String> vars) {
        return mini.deserialize(raw(key), resolver(vars));
    }

    public Component render(String key, String... kv) {
        return render(key, Outcome.vars(kv));
    }

    /** Menu text: same as {@link #render} but without the italics item names and lore get by default. */
    public Component item(String key, Map<String, String> vars) {
        return render(key, vars).decoration(TextDecoration.ITALIC, false);
    }

    public List<Component> lore(String key, Map<String, String> vars, TagResolver... extra) {
        List<Component> lines = new ArrayList<>();
        for (String line : active.getStringList(key)) {
            lines.add(mini.deserialize(line, resolver(vars, extra)).decoration(TextDecoration.ITALIC, false));
        }
        return lines;
    }

    public void send(Audience audience, String key, Map<String, String> vars) {
        if (active.isList(key)) {
            for (String line : active.getStringList(key)) {
                audience.sendMessage(mini.deserialize(line, resolver(vars)));
            }
            return;
        }
        String raw = raw(key);
        if (!raw.isEmpty()) {
            audience.sendMessage(mini.deserialize(raw, resolver(vars)));
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
        String itemKey = vars.get("item_key");
        if (itemKey != null) {
            builder.resolver(Placeholder.component("item", itemName(itemKey)));
        }
        builder.resolvers(extra);
        return builder.build();
    }

    public static Component itemName(String itemKey) {
        Material material = Material.matchMaterial(itemKey);
        if (material == null) {
            return Component.text(itemKey);
        }
        return Component.translatable(material.translationKey());
    }
}
