package io.github.tuskworks.orders.listener;

import io.github.tuskworks.orders.TuskOrders;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public final class PlayerListener implements Listener {

    private final TuskOrders plugin;

    public PlayerListener(TuskOrders plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        plugin.service().updateOwnerName(player.getUniqueId(), player.getName());
        // Let the join messages settle before reminding them
        player.getScheduler().runDelayed(plugin, task -> {
            int waiting = plugin.service().uncollectedCount(player.getUniqueId());
            if (waiting > 0) {
                plugin.messages().send(player, "notify.waiting", "count", String.valueOf(waiting));
            }
        }, null, 60L);
    }
}
