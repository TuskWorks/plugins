package io.github.tuskworks.crates.crate;

import net.kyori.adventure.text.Component;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * One possible outcome of a crate.
 *
 * @param displayItem item shown in previews and animations
 * @param items       items handed to the player (may be empty for command-only rewards)
 * @param commands    console commands; {@code <player>} / {@code {player}} are replaced with the player name
 */
public record Reward(
        String id,
        double weight,
        Component name,
        ItemStack displayItem,
        List<ItemStack> items,
        List<String> commands,
        boolean broadcast) {

    public Reward {
        displayItem = displayItem.clone();
        items = items.stream().map(ItemStack::clone).toList();
        commands = List.copyOf(commands);
    }

    @Override
    public ItemStack displayItem() {
        return displayItem.clone();
    }

    @Override
    public List<ItemStack> items() {
        return items.stream().map(ItemStack::clone).toList();
    }
}
