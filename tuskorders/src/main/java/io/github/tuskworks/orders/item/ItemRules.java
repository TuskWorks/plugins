package io.github.tuskworks.orders.item;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

/**
 * Orders are placed per item type, and only plain items count as a delivery: nothing renamed,
 * enchanted, damaged or carrying contents, so buyers always get what they paid for.
 */
public final class ItemRules {

    private final List<Pattern> blacklist;

    public ItemRules(List<String> blacklist) {
        List<Pattern> patterns = new ArrayList<>();
        for (String entry : blacklist) {
            String glob = entry.trim().toUpperCase(Locale.ROOT);
            int colon = glob.indexOf(':');
            if (colon >= 0) {
                glob = glob.substring(colon + 1);
            }
            patterns.add(Pattern.compile(Pattern.quote(glob).replace("*", "\\E.*\\Q")));
        }
        this.blacklist = List.copyOf(patterns);
    }

    /** Resolves player input like {@code oak log}, {@code OAK_LOG} or {@code minecraft:oak_log}. */
    public static Optional<Material> parse(String input) {
        Material material = Material.matchMaterial(input.trim());
        if (material == null || !material.isItem() || material.isAir()) {
            return Optional.empty();
        }
        return Optional.of(material);
    }

    public boolean allowed(Material material) {
        String name = material.name();
        return blacklist.stream().noneMatch(p -> p.matcher(name).matches());
    }

    public List<String> suggestions(String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        return Arrays.stream(Material.values())
                .filter(m -> !m.isLegacy() && m.isItem() && !m.isAir() && allowed(m))
                .map(m -> m.getKey().getKey())
                .filter(k -> k.startsWith(p))
                .sorted()
                .limit(50)
                .toList();
    }

    public static String key(Material material) {
        return material.getKey().toString();
    }

    /** True if the stack is exactly the untouched item an order for {@code material} expects. */
    public static boolean isPlain(ItemStack stack, Material material) {
        return stack != null && stack.getType() == material && stack.isSimilar(new ItemStack(material));
    }
}
