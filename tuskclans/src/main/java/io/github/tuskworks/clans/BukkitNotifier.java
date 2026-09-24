package io.github.tuskworks.clans;

import io.github.tuskworks.clans.lang.Messages;
import io.github.tuskworks.clans.model.Clan;
import io.github.tuskworks.clans.model.ClanMember;
import io.github.tuskworks.clans.service.Notifier;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

final class BukkitNotifier implements Notifier {

    private final Messages messages;

    BukkitNotifier(Messages messages) {
        this.messages = messages;
    }

    @Override
    public void player(UUID playerId, String key, Map<String, String> vars) {
        Player player = Bukkit.getPlayer(playerId);
        if (player != null) {
            messages.send(player, key, vars);
        }
    }

    @Override
    public void clan(Clan clan, String key, Map<String, String> vars, @Nullable UUID except) {
        for (ClanMember member : clan.members()) {
            if (!member.id().equals(except)) {
                player(member.id(), key, vars);
            }
        }
    }
}
