package io.github.tuskworks.clans;

import io.github.tuskworks.clans.command.ClanCommand;
import io.github.tuskworks.clans.config.PluginConfig;
import io.github.tuskworks.clans.home.HomeTeleporter;
import io.github.tuskworks.clans.hook.ClanPlaceholders;
import io.github.tuskworks.clans.hook.EconomyHook;
import io.github.tuskworks.clans.lang.Messages;
import io.github.tuskworks.clans.listener.ChatListener;
import io.github.tuskworks.clans.listener.CombatListener;
import io.github.tuskworks.clans.listener.ConnectionListener;
import io.github.tuskworks.clans.listener.MenuListener;
import io.github.tuskworks.clans.model.Clan;
import io.github.tuskworks.clans.service.ClanService;
import io.github.tuskworks.clans.storage.ClanStorage;
import io.github.tuskworks.clans.storage.JsonClanStorage;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.io.IOException;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.Nullable;

public final class TuskClans extends JavaPlugin {

    private volatile PluginConfig config;
    private Messages messages;
    private ClanStorage storage;
    private ClanService service;
    private ClanChat chat;
    private HomeTeleporter homes;
    private @Nullable EconomyHook economy;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        config = PluginConfig.from(getConfig(), getLogger());
        messages = new Messages(this);
        messages.load(config.language());

        storage = new JsonClanStorage(getDataFolder().toPath().resolve("clans"), getLogger());
        service = new ClanService(config.clan(), storage, new BukkitNotifier(messages), Clock.systemUTC());
        try {
            List<Clan> clans = storage.loadAll();
            service.load(clans);
            getLogger().info("Loaded " + clans.size() + " clan(s)");
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "Could not load clan data, disabling to protect it", e);
            storage.close();
            storage = null;
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        chat = new ClanChat(service, messages);
        homes = new HomeTeleporter(this, messages, this::config);

        PluginManager pm = getServer().getPluginManager();
        pm.registerEvents(new CombatListener(service, this::config), this);
        pm.registerEvents(new ChatListener(this, chat, this::config), this);
        pm.registerEvents(new ConnectionListener(service, messages, chat, homes), this);
        pm.registerEvents(new MenuListener(), this);

        List<String> aliases = config.aliases();
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
                event.registrar().register("clan", "Clans and teams", aliases, new ClanCommand(this)));

        // Only touch EconomyHook (and so Vault classes) when Vault is installed.
        economy = pm.getPlugin("Vault") != null ? EconomyHook.tryCreate() : null;
        if (config.createCost() > 0 && economy == null) {
            getLogger().warning("clan.create-cost is set but no Vault economy was found; creating clans is free");
        }
        if (pm.getPlugin("PlaceholderAPI") != null) {
            new ClanPlaceholders(this).register();
            getLogger().info("Registered PlaceholderAPI placeholders (%tuskclans_...%)");
        }

        long period = config.autosaveSeconds();
        Bukkit.getAsyncScheduler().runAtFixedRate(this, task -> {
            service.flushDirty();
            service.purgeExpired();
        }, period, period, TimeUnit.SECONDS);
    }

    @Override
    public void onDisable() {
        Bukkit.getAsyncScheduler().cancelTasks(this);
        if (service != null) {
            service.flushDirty();
        }
        if (storage != null) {
            storage.close();
        }
    }

    public void reload() {
        reloadConfig();
        PluginConfig fresh = PluginConfig.from(getConfig(), getLogger());
        messages.load(fresh.language());
        service.settings(fresh.clan());
        config = fresh;
        if (economy == null && getServer().getPluginManager().getPlugin("Vault") != null) {
            economy = EconomyHook.tryCreate();
        }
    }

    public Component formattedTag(Clan clan) {
        return messages.renderRaw(config.chat().tagFormat(), Map.of("tag", clan.tag(), "name", clan.name()));
    }

    public PluginConfig config() {
        return config;
    }

    public Messages messages() {
        return messages;
    }

    public ClanService service() {
        return service;
    }

    public ClanChat chat() {
        return chat;
    }

    public HomeTeleporter homes() {
        return homes;
    }

    public @Nullable EconomyHook economy() {
        return economy;
    }
}
