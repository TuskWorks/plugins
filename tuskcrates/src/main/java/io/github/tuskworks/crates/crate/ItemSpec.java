package io.github.tuskworks.crates.crate;

import io.github.tuskworks.crates.util.Text;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;

import java.util.Base64;
import java.util.Locale;

/** Reads item definitions from YAML: either {@code base64} or {@code material} with optional meta. */
public final class ItemSpec {

    private ItemSpec() {
    }

    public static ItemStack parse(ConfigurationSection section, TagResolver... resolvers) {
        if (section == null) {
            throw new IllegalArgumentException("missing item section");
        }
        String base64 = section.getString("base64");
        if (base64 != null) {
            ItemStack item = ItemStack.deserializeBytes(Base64.getDecoder().decode(base64));
            if (section.contains("amount")) {
                item.setAmount(section.getInt("amount"));
            }
            return item;
        }

        String materialName = section.getString("material");
        Material material = materialName == null ? null : Material.matchMaterial(materialName);
        if (material == null || !material.isItem() || material.isAir()) {
            throw new IllegalArgumentException("invalid material '" + materialName + "' at " + section.getCurrentPath());
        }
        ItemStack item = new ItemStack(material, Math.max(1, section.getInt("amount", 1)));
        item.editMeta(meta -> {
            if (section.contains("name")) {
                meta.displayName(Text.item(section.getString("name"), resolvers));
            }
            if (section.contains("lore")) {
                meta.lore(Text.itemLines(section.getStringList("lore"), resolvers));
            }
            ConfigurationSection enchants = section.getConfigurationSection("enchantments");
            if (enchants != null) {
                var registry = RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT);
                for (String key : enchants.getKeys(false)) {
                    NamespacedKey id = NamespacedKey.fromString(key.toLowerCase(Locale.ROOT));
                    var enchantment = id == null ? null : registry.get(id);
                    if (enchantment == null) {
                        throw new IllegalArgumentException("unknown enchantment '" + key + "' at " + enchants.getCurrentPath());
                    }
                    meta.addEnchant(enchantment, enchants.getInt(key, 1), true);
                }
            }
            if (section.contains("glow")) {
                meta.setEnchantmentGlintOverride(section.getBoolean("glow"));
            }
            if (section.contains("item-model")) {
                meta.setItemModel(NamespacedKey.fromString(section.getString("item-model", "")));
            }
            if (section.contains("custom-model-data")) {
                applyCustomModelData(meta, section.getInt("custom-model-data"));
            }
            for (String flag : section.getStringList("flags")) {
                meta.addItemFlags(ItemFlag.valueOf(flag.toUpperCase(Locale.ROOT)));
            }
        });
        return item;
    }

    public static String toBase64(ItemStack item) {
        return Base64.getEncoder().encodeToString(item.serializeAsBytes());
    }

    @SuppressWarnings("deprecation") // superseded by the custom_model_data component, still widely used by packs
    private static void applyCustomModelData(org.bukkit.inventory.meta.ItemMeta meta, int value) {
        meta.setCustomModelData(value);
    }
}
