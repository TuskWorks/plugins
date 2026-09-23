package io.github.tuskworks.clans.home;

import io.github.tuskworks.clans.config.PluginConfig;
import io.github.tuskworks.clans.lang.Messages;
import io.github.tuskworks.clans.model.ClanHome;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;

/** Warmup + cooldown teleports to the clan home, using Folia-safe entity schedulers. */
public final class HomeTeleporter {

    private static final long CHECK_PERIOD_TICKS = 5;

    private final Plugin plugin;
    private final Messages messages;
    private final Supplier<PluginConfig> config;
    private final Map<UUID, Long> lastUse = new ConcurrentHashMap<>();
    private final Map<UUID, ScheduledTask> pending = new ConcurrentHashMap<>();

    public HomeTeleporter(Plugin plugin, Messages messages, Supplier<PluginConfig> config) {
        this.plugin = plugin;
        this.messages = messages;
        this.config = config;
    }

    public void teleport(Player player, ClanHome home) {
        World world = Bukkit.getWorld(home.world());
        if (world == null) {
            messages.send(player, "error.home-world-missing", "world", home.world());
            return;
        }
        PluginConfig.Home settings = config.get().home();
        boolean bypass = player.hasPermission("tuskclans.bypass.cooldown");
        UUID id = player.getUniqueId();
        long now = System.currentTimeMillis();
        if (!bypass) {
            long readyAt = lastUse.getOrDefault(id, 0L) + settings.cooldownSeconds() * 1000L;
            if (readyAt > now) {
                long seconds = (readyAt - now + 999) / 1000;
                messages.send(player, "error.home-cooldown", "seconds", String.valueOf(seconds));
                return;
            }
        }
        Location target = new Location(world, home.x(), home.y(), home.z(), home.yaw(), home.pitch());
        int warmup = bypass ? 0 : settings.warmupSeconds();
        cancel(id);
        if (warmup <= 0) {
            go(player, target);
            return;
        }
        messages.send(player, "home.warmup", "seconds", String.valueOf(warmup));
        Location start = player.getLocation();
        long totalChecks = Math.max(1, warmup * 20L / CHECK_PERIOD_TICKS);
        long[] checks = {0};
        ScheduledTask task = player.getScheduler().runAtFixedRate(plugin, t -> {
            if (settings.cancelOnMove() && moved(start, player.getLocation())) {
                t.cancel();
                pending.remove(id, t);
                messages.send(player, "home.cancelled");
                return;
            }
            if (++checks[0] >= totalChecks) {
                t.cancel();
                pending.remove(id, t);
                go(player, target);
            }
        }, () -> pending.remove(id), CHECK_PERIOD_TICKS, CHECK_PERIOD_TICKS);
        if (task != null) {
            pending.put(id, task);
        }
    }

    public void cancel(UUID playerId) {
        ScheduledTask task = pending.remove(playerId);
        if (task != null) {
            task.cancel();
        }
    }

    private void go(Player player, Location target) {
        lastUse.put(player.getUniqueId(), System.currentTimeMillis());
        player.teleportAsync(target, PlayerTeleportEvent.TeleportCause.PLUGIN).thenAccept(ok -> {
            if (ok) {
                messages.send(player, "home.teleported");
            }
        });
    }

    private static boolean moved(Location from, Location to) {
        return from.getWorld() != to.getWorld() || from.distanceSquared(to) > 0.25;
    }
}
