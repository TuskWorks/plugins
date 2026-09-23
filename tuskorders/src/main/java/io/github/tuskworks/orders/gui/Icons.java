package io.github.tuskworks.orders.gui;

import java.time.Duration;
import java.util.List;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

final class Icons {

    private static final int BAR_LENGTH = 20;

    private Icons() {
    }

    static ItemStack icon(Material material, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(material.isItem() ? material : Material.BARRIER);
        ItemMeta meta = item.getItemMeta();
        if (name != null) {
            meta.displayName(name);
        }
        meta.lore(lore);
        // Only long-standing flags: newer ones differ between 1.21.4 and 26.x
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS);
        item.setItemMeta(meta);
        return item;
    }

    static ItemStack icon(Material material, Component name) {
        return icon(material, name, List.of());
    }

    static ItemStack filler() {
        return icon(Material.GRAY_STAINED_GLASS_PANE, Component.empty());
    }

    /** A MiniMessage progress bar, e.g. {@code <green>||||||<dark_gray>||||}. */
    static String bar(int done, int total) {
        int filled = total <= 0 ? 0 : (int) Math.round((double) done / total * BAR_LENGTH);
        return "<green>" + "|".repeat(filled) + "<dark_gray>" + "|".repeat(BAR_LENGTH - filled);
    }

    /** Compact time left, e.g. {@code 3d 4h}, {@code 5h 12m} or {@code 42m}. */
    static String timeLeft(Duration left) {
        if (left.isNegative() || left.isZero()) {
            return "0m";
        }
        long days = left.toDays();
        long hours = left.toHoursPart();
        long minutes = left.toMinutesPart();
        if (days > 0) {
            return days + "d " + hours + "h";
        }
        if (hours > 0) {
            return hours + "h " + minutes + "m";
        }
        return Math.max(1, minutes) + "m";
    }
}
