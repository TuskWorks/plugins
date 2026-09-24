package io.github.tuskworks.clans.model;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A clan. Reads are safe from any thread (chat and region threads on Folia);
 * mutations must go through {@link io.github.tuskworks.clans.service.ClanService}.
 */
public final class Clan {

    private final UUID id;
    private final long createdAt;
    private final Map<UUID, ClanMember> members = new ConcurrentHashMap<>();
    private final Set<UUID> allies = ConcurrentHashMap.newKeySet();
    private final AtomicInteger kills = new AtomicInteger();
    private final AtomicInteger deaths = new AtomicInteger();
    private volatile String tag;
    private volatile String name;
    private volatile ClanHome home;
    private volatile boolean friendlyFire;
    private volatile boolean open;

    public Clan(UUID id, String tag, String name, long createdAt) {
        this.id = id;
        this.tag = tag;
        this.name = name;
        this.createdAt = createdAt;
    }

    public UUID id() {
        return id;
    }

    public String tag() {
        return tag;
    }

    public void tag(String tag) {
        this.tag = tag;
    }

    public String name() {
        return name;
    }

    public void name(String name) {
        this.name = name;
    }

    public long createdAt() {
        return createdAt;
    }

    public Collection<ClanMember> members() {
        return Collections.unmodifiableCollection(members.values());
    }

    public Optional<ClanMember> member(UUID playerId) {
        return Optional.ofNullable(members.get(playerId));
    }

    public boolean isMember(UUID playerId) {
        return members.containsKey(playerId);
    }

    public int size() {
        return members.size();
    }

    public void putMember(ClanMember member) {
        members.put(member.id(), member);
    }

    public void removeMember(UUID playerId) {
        members.remove(playerId);
    }

    public ClanMember leader() {
        return members.values().stream()
                .filter(m -> m.role() == ClanRole.LEADER)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Clan " + tag + " has no leader"));
    }

    public Set<UUID> allies() {
        return Collections.unmodifiableSet(allies);
    }

    public boolean isAlliedWith(UUID clanId) {
        return allies.contains(clanId);
    }

    public void addAlly(UUID clanId) {
        allies.add(clanId);
    }

    public void removeAlly(UUID clanId) {
        allies.remove(clanId);
    }

    public Optional<ClanHome> home() {
        return Optional.ofNullable(home);
    }

    public void home(ClanHome home) {
        this.home = home;
    }

    public boolean friendlyFire() {
        return friendlyFire;
    }

    public void friendlyFire(boolean friendlyFire) {
        this.friendlyFire = friendlyFire;
    }

    public boolean open() {
        return open;
    }

    public void open(boolean open) {
        this.open = open;
    }

    public int kills() {
        return kills.get();
    }

    public int deaths() {
        return deaths.get();
    }

    public void kills(int value) {
        kills.set(value);
    }

    public void deaths(int value) {
        deaths.set(value);
    }

    public void addKill() {
        kills.incrementAndGet();
    }

    public void addDeath() {
        deaths.incrementAndGet();
    }

    public double kdr() {
        int d = deaths();
        return d == 0 ? kills() : (double) kills() / d;
    }
}
