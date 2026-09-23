package io.github.tuskworks.clans.listener;

import io.github.tuskworks.clans.ClanChat;
import io.github.tuskworks.clans.TuskClans;
import io.github.tuskworks.clans.config.PluginConfig;
import io.github.tuskworks.clans.model.Clan;
import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.Optional;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

public final class ChatListener implements Listener {

    private final TuskClans plugin;
    private final ClanChat chat;
    private final Supplier<PluginConfig> config;

    public ChatListener(TuskClans plugin, ClanChat chat, Supplier<PluginConfig> config) {
        this.plugin = plugin;
        this.chat = chat;
        this.config = config;
    }

    /** Players who toggled clan/ally chat talk there instead of public chat. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onChannelChat(AsyncChatEvent event) {
        ClanChat.Mode mode = chat.mode(event.getPlayer().getUniqueId());
        if (mode == ClanChat.Mode.PUBLIC) {
            return;
        }
        event.setCancelled(true);
        chat.send(event.getPlayer(), event.message(), mode == ClanChat.Mode.ALLY);
    }

    /** Prefixes the clan tag in public chat. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPublicChat(AsyncChatEvent event) {
        if (!config.get().chat().showTag()) {
            return;
        }
        Optional<Clan> clan = plugin.service().clanOf(event.getPlayer().getUniqueId());
        if (clan.isEmpty()) {
            return;
        }
        Component tag = plugin.formattedTag(clan.get());
        ChatRenderer original = event.renderer();
        event.renderer((source, displayName, message, viewer) ->
                tag.append(original.render(source, displayName, message, viewer)));
    }
}
