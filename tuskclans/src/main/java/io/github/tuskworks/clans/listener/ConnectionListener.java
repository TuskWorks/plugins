package io.github.tuskworks.clans.listener;

import io.github.tuskworks.clans.ClanChat;
import io.github.tuskworks.clans.home.HomeTeleporter;
import io.github.tuskworks.clans.lang.Messages;
import io.github.tuskworks.clans.model.Clan;
import io.github.tuskworks.clans.service.ClanService;
import java.util.List;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public final class ConnectionListener implements Listener {

    private final ClanService service;
    private final Messages messages;
    private final ClanChat chat;
    private final HomeTeleporter homes;

    public ConnectionListener(ClanService service, Messages messages, ClanChat chat, HomeTeleporter homes) {
        this.service = service;
        this.messages = messages;
        this.chat = chat;
        this.homes = homes;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        service.updateName(player.getUniqueId(), player.getName());
        List<Clan> invites = service.pendingInvites(player.getUniqueId());
        if (!invites.isEmpty()) {
            messages.send(player, "invite.reminder",
                    "tags", String.join(", ", invites.stream().map(Clan::tag).toList()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        chat.reset(event.getPlayer().getUniqueId());
        // Keep the cooldown so relogging doesn't reset it.
        homes.cancel(event.getPlayer().getUniqueId());
    }
}
