package io.github.tuskworks.clans.service;

import io.github.tuskworks.clans.model.Clan;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Delivers side-effect messages (to players other than the actor). */
public interface Notifier {

    void player(UUID playerId, String key, Map<String, String> vars);

    void clan(Clan clan, String key, Map<String, String> vars, @Nullable UUID except);
}
