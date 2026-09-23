package io.github.tuskworks.crates.crate;

import io.github.tuskworks.crates.util.Weighted;
import net.kyori.adventure.text.Component;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * @param displayNameRaw MiniMessage source of {@link #displayName()}, used for the {@code <crate>} tag
 * @param keyTemplate    key item before it is tagged with the crate id
 */
public record Crate(
        String id,
        String displayNameRaw,
        Component displayName,
        AnimationType animation,
        ItemStack keyTemplate,
        List<Reward> rewards,
        List<String> hologramLines,
        boolean showInMenu,
        File file) {

    public Crate {
        keyTemplate = keyTemplate.clone();
        rewards = List.copyOf(rewards);
        hologramLines = List.copyOf(hologramLines);
    }

    @Override
    public ItemStack keyTemplate() {
        return keyTemplate.clone();
    }

    public Reward pickReward() {
        return Weighted.pick(rewards, Reward::weight, ThreadLocalRandom.current());
    }

    public double chancePercent(Reward reward) {
        return Weighted.chancePercent(reward, rewards, Reward::weight);
    }
}
