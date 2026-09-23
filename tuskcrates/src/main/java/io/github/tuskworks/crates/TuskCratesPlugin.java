package io.github.tuskworks.crates;

import io.github.tuskworks.crates.command.CrateCommands;
import io.github.tuskworks.crates.config.Messages;
import io.github.tuskworks.crates.config.PluginConfig;
import io.github.tuskworks.crates.crate.CrateRegistry;
import io.github.tuskworks.crates.hologram.HologramService;
import io.github.tuskworks.crates.key.KeyService;
import io.github.tuskworks.crates.key.KeyStorage;
import io.github.tuskworks.crates.listener.CrateBlockListener;
import io.github.tuskworks.crates.listener.MenuListener;
import io.github.tuskworks.crates.listener.PlayerListener;
import io.github.tuskworks.crates.location.CrateLocations;
import io.github.tuskworks.crates.open.OpenService;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.concurrent.TimeUnit;

public final class TuskCratesPlugin extends JavaPlugin {

    private volatile PluginConfig settings;
    private volatile Messages messages;
    private CrateRegistry crates;
    private KeyService keys;
    private KeyStorage keyStorage;
    private CrateLocations locations;
    private HologramService holograms;
    private OpenService openService;
    private ScheduledTask autosave;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        settings = PluginConfig.from(getConfig());
        messages = Messages.load(this);

        crates = new CrateRegistry(this);
        int loaded = crates.load();
        keys = new KeyService(this);
        keyStorage = new KeyStorage(new File(getDataFolder(), "data/players"), getLogger(),
                id -> getServer().getPlayer(id) != null);
        locations = new CrateLocations(new File(getDataFolder(), "locations.yml"), getLogger());
        locations.load();
        holograms = new HologramService(this);
        openService = new OpenService(this);

        var pm = getServer().getPluginManager();
        pm.registerEvents(new MenuListener(), this);
        pm.registerEvents(new CrateBlockListener(this), this);
        pm.registerEvents(new PlayerListener(this), this);
        CrateCommands.register(this);

        scheduleAutosave();
        holograms.refreshAll();
        getLogger().info("Loaded " + loaded + " crate(s) and " + locations.all().size() + " crate block(s).");
    }

    @Override
    public void onDisable() {
        if (autosave != null) {
            autosave.cancel();
        }
        if (openService != null) {
            openService.shutdown();
        }
        if (holograms != null) {
            holograms.removeAll();
        }
        if (keyStorage != null) {
            keyStorage.saveAll();
        }
        if (locations != null) {
            locations.save();
        }
    }

    /** Reloads config, messages and crates. Returns the number of crates loaded. */
    public int reload() {
        reloadConfig();
        settings = PluginConfig.from(getConfig());
        messages = Messages.load(this);
        int count = crates.load();
        scheduleAutosave();
        holograms.refreshAll();
        return count;
    }

    public void saveLocationsAsync() {
        getServer().getAsyncScheduler().runNow(this, task -> locations.save());
    }

    private void scheduleAutosave() {
        if (autosave != null) {
            autosave.cancel();
        }
        long minutes = settings.autosaveMinutes();
        autosave = getServer().getAsyncScheduler().runAtFixedRate(this, task -> keyStorage.saveDirty(),
                minutes, minutes, TimeUnit.MINUTES);
    }

    public PluginConfig settings() {
        return settings;
    }

    public Messages messages() {
        return messages;
    }

    public CrateRegistry crates() {
        return crates;
    }

    public KeyService keys() {
        return keys;
    }

    public KeyStorage keyStorage() {
        return keyStorage;
    }

    public CrateLocations locations() {
        return locations;
    }

    public HologramService holograms() {
        return holograms;
    }

    public OpenService openService() {
        return openService;
    }
}
