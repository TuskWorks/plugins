package io.github.tuskworks.clans.service;

import io.github.tuskworks.clans.model.Clan;
import io.github.tuskworks.clans.storage.ClanStorage;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

final class TestSupport {

    private TestSupport() {
    }

    static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-09-24T00:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    static final class MemoryStorage implements ClanStorage {
        final Set<UUID> saved = new HashSet<>();
        final Set<UUID> deleted = new HashSet<>();

        @Override
        public List<Clan> loadAll() {
            return List.of();
        }

        @Override
        public void save(Clan clan) {
            saved.add(clan.id());
        }

        @Override
        public void delete(UUID clanId) {
            deleted.add(clanId);
        }

        @Override
        public void close() {
        }
    }

    record Sent(UUID to, String key, Map<String, String> vars) {
    }

    static final class RecordingNotifier implements Notifier {
        final List<Sent> sent = new ArrayList<>();

        @Override
        public void player(UUID playerId, String key, Map<String, String> vars) {
            sent.add(new Sent(playerId, key, vars));
        }

        @Override
        public void clan(Clan clan, String key, Map<String, String> vars, @Nullable UUID except) {
            clan.members().stream()
                    .filter(m -> !m.id().equals(except))
                    .forEach(m -> sent.add(new Sent(m.id(), key, vars)));
        }

        boolean received(UUID player, String key) {
            return sent.stream().anyMatch(s -> s.to().equals(player) && s.key().equals(key));
        }
    }
}
