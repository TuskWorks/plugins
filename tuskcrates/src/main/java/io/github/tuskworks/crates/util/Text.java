package io.github.tuskworks.crates.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.inventory.ItemStack;

import java.util.List;

public final class Text {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private Text() {
    }

    public static Component parse(String input, TagResolver... resolvers) {
        return MINI.deserialize(input == null ? "" : input, resolvers);
    }

    /** Parses text for item names/lore, where Minecraft would otherwise italicize it. */
    public static Component item(String input, TagResolver... resolvers) {
        return parse(input, resolvers).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    public static List<Component> itemLines(List<String> lines, TagResolver... resolvers) {
        return lines.stream().map(line -> item(line, resolvers)).toList();
    }

    /** The custom name of an item, or its translated vanilla name. */
    public static Component nameOf(ItemStack item) {
        var meta = item.getItemMeta();
        if (meta != null && meta.hasDisplayName()) {
            return meta.displayName();
        }
        return Component.translatable(item.getType().translationKey());
    }
}
