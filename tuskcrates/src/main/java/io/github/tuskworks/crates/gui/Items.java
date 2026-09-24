package io.github.tuskworks.crates.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

final class Items {

    private Items() {
    }

    static ItemStack filler(Material material) {
        return named(material, Component.empty());
    }

    static ItemStack named(Material material, Component name) {
        ItemStack item = new ItemStack(material);
        item.editMeta(meta -> {
            meta.displayName(name);
            meta.setHideTooltip(name.equals(Component.empty()));
        });
        return item;
    }

    static ItemStack withExtraLore(ItemStack base, List<Component> extra) {
        ItemStack item = base.clone();
        item.editMeta(meta -> {
            List<Component> lore = meta.hasLore() ? new ArrayList<>(meta.lore()) : new ArrayList<>();
            if (!lore.isEmpty()) {
                lore.add(Component.empty());
            }
            lore.addAll(extra);
            meta.lore(lore);
        });
        return item;
    }
}
