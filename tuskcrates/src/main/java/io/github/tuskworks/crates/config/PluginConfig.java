package io.github.tuskworks.crates.config;

import io.github.tuskworks.crates.key.KeyType;
import org.bukkit.configuration.file.FileConfiguration;

public record PluginConfig(
        KeyType defaultKeyType,
        boolean useVirtualAtBlock,
        boolean knockbackWithoutKey,
        double knockbackStrength,
        boolean requireEmptySlot,
        boolean hologramEnabled,
        double hologramHeight,
        int rouletteSteps,
        int displaySteps,
        int displayRevealTicks,
        int autosaveMinutes) {

    public static PluginConfig from(FileConfiguration c) {
        return new PluginConfig(
                KeyType.parse(c.getString("keys.default-type"), KeyType.VIRTUAL),
                c.getBoolean("keys.use-virtual-at-block", true),
                c.getBoolean("crate-block.knockback-without-key", true),
                c.getDouble("crate-block.knockback-strength", 0.8),
                c.getBoolean("open.require-empty-slot", true),
                c.getBoolean("hologram.enabled", true),
                c.getDouble("hologram.height", 1.35),
                Math.max(5, c.getInt("animation.roulette.steps", 32)),
                Math.max(5, c.getInt("animation.display.steps", 20)),
                Math.max(10, c.getInt("animation.display.reveal-ticks", 50)),
                Math.max(1, c.getInt("storage.autosave-minutes", 5)));
    }
}
