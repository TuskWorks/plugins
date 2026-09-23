package io.github.tuskworks.clans.config;

import io.github.tuskworks.clans.service.ClanSettings;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;
import org.bukkit.configuration.ConfigurationSection;

public record PluginConfig(
        String language,
        List<String> aliases,
        ClanSettings clan,
        double createCost,
        Home home,
        Chat chat,
        Set<String> disabledWorlds,
        int autosaveSeconds) {

    public record Home(boolean enabled, int warmupSeconds, int cooldownSeconds, boolean cancelOnMove) {
    }

    public record Chat(boolean showTag, String tagFormat) {
    }

    public boolean isDisabledWorld(String world) {
        return disabledWorlds.contains(world.toLowerCase(Locale.ROOT));
    }

    public static PluginConfig from(ConfigurationSection c, Logger logger) {
        Pattern pattern;
        String rawPattern = c.getString("clan.tag.pattern", "^[A-Za-z0-9]+$");
        try {
            pattern = Pattern.compile(rawPattern);
        } catch (PatternSyntaxException e) {
            logger.warning("Invalid clan.tag.pattern '" + rawPattern + "', falling back to letters and digits");
            pattern = Pattern.compile("^[A-Za-z0-9]+$");
        }
        int tagMin = Math.max(1, c.getInt("clan.tag.min-length", 2));
        int tagMax = Math.max(tagMin, c.getInt("clan.tag.max-length", 6));
        ClanSettings clan = new ClanSettings(
                tagMin,
                tagMax,
                pattern,
                Math.max(1, c.getInt("clan.name-max-length", 24)),
                c.getInt("clan.max-members", 20),
                c.getInt("clan.max-allies", 3),
                Duration.ofSeconds(Math.max(5, c.getInt("clan.invite-expire-seconds", 120))),
                c.getBoolean("friendly-fire.default", false),
                c.getBoolean("friendly-fire.protect-allies", true));
        Home home = new Home(
                c.getBoolean("home.enabled", true),
                Math.max(0, c.getInt("home.warmup-seconds", 3)),
                Math.max(0, c.getInt("home.cooldown-seconds", 30)),
                c.getBoolean("home.cancel-on-move", true));
        Chat chat = new Chat(
                c.getBoolean("chat.show-tag", true),
                c.getString("chat.tag-format", "<gray>[<white><tag></white>]</gray> "));
        Set<String> disabledWorlds = c.getStringList("disabled-worlds").stream()
                .map(w -> w.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
        return new PluginConfig(
                c.getString("language", "en"),
                List.copyOf(c.getStringList("command.aliases")),
                clan,
                Math.max(0, c.getDouble("clan.create-cost", 0)),
                home,
                chat,
                disabledWorlds,
                Math.max(10, c.getInt("autosave-seconds", 60)));
    }
}
