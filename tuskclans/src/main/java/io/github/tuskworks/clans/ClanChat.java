package io.github.tuskworks.clans;

import io.github.tuskworks.clans.lang.Messages;
import io.github.tuskworks.clans.model.Clan;
import io.github.tuskworks.clans.model.ClanMember;
import io.github.tuskworks.clans.service.ClanService;
import io.github.tuskworks.clans.service.Outcome;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/** Clan and ally chat channels, plus the per-player "talk in channel" toggle. */
public final class ClanChat {

    public enum Mode { PUBLIC, CLAN, ALLY }

    private final ClanService service;
    private final Messages messages;
    private final Map<UUID, Mode> modes = new ConcurrentHashMap<>();

    public ClanChat(ClanService service, Messages messages) {
        this.service = service;
        this.messages = messages;
    }

    public Mode mode(UUID playerId) {
        return modes.getOrDefault(playerId, Mode.PUBLIC);
    }

    /** Toggles between the given channel and public chat; returns the new mode. */
    public Mode toggle(UUID playerId, Mode channel) {
        Mode next = mode(playerId) == channel ? Mode.PUBLIC : channel;
        if (next == Mode.PUBLIC) {
            modes.remove(playerId);
        } else {
            modes.put(playerId, next);
        }
        return next;
    }

    public void reset(UUID playerId) {
        modes.remove(playerId);
    }

    /**
     * The player is no longer in a clan (left, kicked or disbanded). If they were talking in a
     * clan channel, switch them back to public chat and tell them, so their next message is
     * neither swallowed nor sent publicly by surprise.
     */
    public void leftClan(UUID playerId) {
        if (modes.remove(playerId) == null) {
            return;
        }
        Player player = Bukkit.getPlayer(playerId);
        if (player != null) {
            messages.send(player, "chat.mode-public");
        }
    }

    /** Sends a message to the sender's clan (and allies when {@code ally} is set). */
    public boolean send(Player sender, Component message, boolean ally) {
        Clan clan = service.clanOf(sender.getUniqueId()).orElse(null);
        if (clan == null) {
            reset(sender.getUniqueId());
            messages.send(sender, "error.not-in-clan");
            return false;
        }
        Set<UUID> recipients = new LinkedHashSet<>();
        clan.members().stream().map(ClanMember::id).forEach(recipients::add);
        if (ally) {
            for (UUID allyId : clan.allies()) {
                service.byId(allyId).ifPresent(a -> a.members().stream().map(ClanMember::id).forEach(recipients::add));
            }
        }
        Map<String, String> vars = Outcome.vars("tag", clan.tag(), "player", sender.getName());
        Component line = messages.render(ally ? "chat.ally-format" : "chat.clan-format", vars,
                Placeholder.component("message", message));
        for (UUID id : recipients) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                p.sendMessage(line);
            }
        }
        Bukkit.getConsoleSender().sendMessage(line);
        return true;
    }
}
