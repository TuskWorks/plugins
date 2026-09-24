package io.github.tuskworks.crates.crate;

import io.github.tuskworks.crates.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

public final class CrateRegistry {

    private static final Pattern ID = Pattern.compile("[a-z0-9_-]+");
    private static final List<String> BUNDLED = List.of("common", "rare", "legendary");

    private final JavaPlugin plugin;
    private final File folder;
    private volatile Map<String, Crate> crates = Map.of();

    public CrateRegistry(JavaPlugin plugin) {
        this.plugin = plugin;
        this.folder = new File(plugin.getDataFolder(), "crates");
    }

    /** Loads every crates/*.yml file. Broken files are logged and skipped. */
    public int load() {
        if (!folder.exists()) {
            BUNDLED.forEach(id -> plugin.saveResource("crates/" + id + ".yml", false));
        }
        File[] files = folder.listFiles((dir, name) -> name.endsWith(".yml"));
        Map<String, Crate> loaded = new LinkedHashMap<>();
        if (files != null) {
            Arrays.sort(files);
            for (File file : files) {
                String id = file.getName().substring(0, file.getName().length() - 4).toLowerCase(Locale.ROOT);
                if (!ID.matcher(id).matches()) {
                    plugin.getLogger().warning("Skipping crates/" + file.getName()
                            + ": file names may only use a-z, 0-9, _ and -");
                    continue;
                }
                try {
                    loaded.put(id, read(id, file));
                } catch (Exception e) {
                    plugin.getLogger().warning("Skipping crates/" + file.getName() + ": " + e.getMessage());
                }
            }
        }
        crates = Map.copyOf(loaded);
        return loaded.size();
    }

    public Optional<Crate> get(String id) {
        return id == null ? Optional.empty() : Optional.ofNullable(crates.get(id.toLowerCase(Locale.ROOT)));
    }

    public List<Crate> all() {
        return crates.values().stream().sorted(Comparator.comparing(Crate::id)).toList();
    }

    public Collection<String> ids() {
        return all().stream().map(Crate::id).toList();
    }

    /** Appends an item reward to a crate file and reloads that crate. */
    public Crate addItemReward(Crate crate, ItemStack item, double weight) throws IOException {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(crate.file());
        ConfigurationSection rewards = yaml.getConfigurationSection("rewards");
        if (rewards == null) {
            rewards = yaml.createSection("rewards");
        }
        String rewardId = uniqueRewardId(rewards, item.getType().getKey().getKey());
        ConfigurationSection reward = rewards.createSection(rewardId);
        reward.set("weight", weight);
        reward.set("item.base64", ItemSpec.toBase64(item));
        reward.set("broadcast", false);
        yaml.save(crate.file());

        Crate reloaded = read(crate.id(), crate.file());
        Map<String, Crate> updated = new LinkedHashMap<>(crates);
        updated.put(crate.id(), reloaded);
        crates = Map.copyOf(updated);
        return reloaded;
    }

    private static String uniqueRewardId(ConfigurationSection rewards, String base) {
        String id = base;
        for (int i = 2; rewards.contains(id); i++) {
            id = base + "-" + i;
        }
        return id;
    }

    private Crate read(String id, File file) {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file);
        } catch (Exception e) {
            String reason = String.valueOf(e.getMessage()).lines().findFirst().orElse("");
            throw new IllegalArgumentException("invalid YAML (" + reason + ")");
        }
        String displayRaw = yaml.getString("display-name", id);
        Component displayName = Text.parse(displayRaw);
        TagResolver crateTag = Placeholder.component("crate", displayName);

        ItemStack key = yaml.isConfigurationSection("key")
                ? ItemSpec.parse(yaml.getConfigurationSection("key"), crateTag)
                : defaultKey(displayName);
        key.setAmount(1);

        List<Reward> rewards = new ArrayList<>();
        ConfigurationSection rewardSection = yaml.getConfigurationSection("rewards");
        if (rewardSection != null) {
            for (String rewardId : rewardSection.getKeys(false)) {
                try {
                    Reward reward = readReward(rewardId, rewardSection.getConfigurationSection(rewardId));
                    if (reward.weight() <= 0) {
                        plugin.getLogger().warning("crates/" + file.getName() + ": reward '" + rewardId
                                + "' has no positive weight, ignoring it");
                        continue;
                    }
                    rewards.add(reward);
                } catch (Exception e) {
                    plugin.getLogger().warning("crates/" + file.getName() + ": skipping reward '" + rewardId
                            + "': " + e.getMessage());
                }
            }
        }
        if (rewards.isEmpty()) {
            throw new IllegalArgumentException("no valid rewards");
        }

        return new Crate(
                id,
                displayRaw,
                displayName,
                AnimationType.parse(yaml.getString("animation")),
                key,
                rewards,
                yaml.getStringList("hologram"),
                yaml.getBoolean("show-in-menu", true),
                file);
    }

    private static Reward readReward(String id, ConfigurationSection section) {
        if (section == null) {
            throw new IllegalArgumentException("not a section");
        }
        ItemStack display = ItemSpec.parse(section.getConfigurationSection("item"));
        List<ItemStack> items = new ArrayList<>();
        if (section.getBoolean("give-item", true)) {
            items.add(display.clone());
        }
        for (Map<?, ?> raw : section.getMapList("extra-items")) {
            ConfigurationSection extra = new YamlConfiguration().createSection("extra-items", raw);
            items.add(ItemSpec.parse(extra));
        }
        Component name = section.contains("name")
                ? Text.item(section.getString("name"))
                : defaultRewardName(display);
        return new Reward(
                id,
                section.getDouble("weight", 1),
                name,
                display,
                items,
                section.getStringList("commands"),
                section.getBoolean("broadcast", false));
    }

    private static Component defaultRewardName(ItemStack item) {
        Component name = Text.nameOf(item);
        return item.getAmount() > 1 ? Component.text(item.getAmount() + "x ").append(name) : name;
    }

    private static ItemStack defaultKey(Component crateName) {
        ItemStack key = new ItemStack(Material.TRIPWIRE_HOOK);
        key.editMeta(meta -> {
            meta.displayName(Text.item("<crate> <white>Key", Placeholder.component("crate", crateName)));
            meta.setEnchantmentGlintOverride(true);
        });
        return key;
    }
}
