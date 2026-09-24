package io.github.tuskworks.clans.service;

import io.github.tuskworks.clans.model.Clan;
import io.github.tuskworks.clans.model.ClanHome;
import io.github.tuskworks.clans.model.ClanMember;
import io.github.tuskworks.clans.model.ClanRole;
import io.github.tuskworks.clans.storage.ClanStorage;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/**
 * All clan rules live here. Mutating methods are synchronized so that command,
 * chat and region threads (Folia) observe a consistent registry.
 */
public final class ClanService {

    private final Map<UUID, Clan> byId = new ConcurrentHashMap<>();
    private final Map<String, Clan> byTag = new ConcurrentHashMap<>();
    private final Map<UUID, Clan> byMember = new ConcurrentHashMap<>();
    /** invitee -> (clan id -> expiry millis) */
    private final Map<UUID, Map<UUID, Long>> invites = new ConcurrentHashMap<>();
    /** target clan id -> (requesting clan id -> expiry millis) */
    private final Map<UUID, Map<UUID, Long>> allyRequests = new ConcurrentHashMap<>();
    private final Set<UUID> dirty = ConcurrentHashMap.newKeySet();

    private final ClanStorage storage;
    private final Notifier notifier;
    private final Clock clock;
    private volatile ClanSettings settings;

    public ClanService(ClanSettings settings, ClanStorage storage, Notifier notifier, Clock clock) {
        this.settings = settings;
        this.storage = storage;
        this.notifier = notifier;
        this.clock = clock;
    }

    public void settings(ClanSettings settings) {
        this.settings = settings;
    }

    public ClanSettings settings() {
        return settings;
    }

    public synchronized void load(Collection<Clan> clans) {
        byId.clear();
        byTag.clear();
        byMember.clear();
        for (Clan clan : clans) {
            index(clan);
        }
        // Drop ally links that point at clans which no longer exist.
        for (Clan clan : clans) {
            for (UUID ally : Set.copyOf(clan.allies())) {
                if (!byId.containsKey(ally)) {
                    clan.removeAlly(ally);
                    persist(clan);
                }
            }
        }
    }

    // ---- lookups -------------------------------------------------------------------------

    public Optional<Clan> clanOf(UUID playerId) {
        return Optional.ofNullable(byMember.get(playerId));
    }

    public Optional<Clan> byTag(String tag) {
        return Optional.ofNullable(byTag.get(normalize(tag)));
    }

    public Optional<Clan> byId(UUID id) {
        return Optional.ofNullable(byId.get(id));
    }

    public Collection<Clan> clans() {
        return List.copyOf(byId.values());
    }

    public List<Clan> top(int limit) {
        return byId.values().stream()
                .sorted(Comparator.<Clan>comparingInt(Clan::kills).reversed()
                        .thenComparing(Comparator.<Clan>comparingInt(Clan::size).reversed())
                        .thenComparing(Clan::tag, String.CASE_INSENSITIVE_ORDER))
                .limit(limit)
                .toList();
    }

    public List<Clan> bySize() {
        return byId.values().stream()
                .sorted(Comparator.<Clan>comparingInt(Clan::size).reversed()
                        .thenComparing(Clan::tag, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    public List<Clan> pendingInvites(UUID playerId) {
        Map<UUID, Long> pending = invites.get(playerId);
        if (pending == null) {
            return List.of();
        }
        long now = clock.millis();
        List<Clan> result = new ArrayList<>();
        pending.forEach((clanId, expires) -> {
            Clan clan = byId.get(clanId);
            if (clan != null && expires > now) {
                result.add(clan);
            }
        });
        return result;
    }

    // ---- lifecycle -----------------------------------------------------------------------

    public synchronized Outcome create(UUID actor, String actorName, String tag, String name) {
        if (byMember.containsKey(actor)) {
            return Outcome.fail("error.already-in-clan");
        }
        ClanSettings s = settings;
        if (tag.length() < s.tagMinLength() || tag.length() > s.tagMaxLength()) {
            return Outcome.fail("error.tag-length",
                    "min", String.valueOf(s.tagMinLength()), "max", String.valueOf(s.tagMaxLength()));
        }
        if (!s.tagPattern().matcher(tag).matches()) {
            return Outcome.fail("error.tag-invalid");
        }
        if (name.isBlank() || name.length() > s.nameMaxLength() || name.chars().anyMatch(Character::isISOControl)) {
            return Outcome.fail("error.name-length", "max", String.valueOf(s.nameMaxLength()));
        }
        if (byTag.containsKey(normalize(tag))) {
            return Outcome.fail("error.tag-taken", "tag", tag);
        }
        long now = clock.millis();
        Clan clan = new Clan(UUID.randomUUID(), tag, name.strip(), now);
        clan.friendlyFire(s.defaultFriendlyFire());
        clan.putMember(new ClanMember(actor, actorName, ClanRole.LEADER, now));
        index(clan);
        invites.remove(actor);
        persist(clan);
        return Outcome.ok("create.success", "tag", clan.tag(), "name", clan.name());
    }

    public synchronized Outcome disband(UUID actor) {
        Optional<Clan> found = clanOf(actor);
        if (found.isEmpty()) {
            return Outcome.fail("error.not-in-clan");
        }
        Clan clan = found.get();
        if (!roleOf(clan, actor).atLeast(ClanRole.LEADER)) {
            return Outcome.fail("error.role-too-low");
        }
        notifier.clan(clan, "disband.notify", Outcome.vars("tag", clan.tag()), actor);
        remove(clan);
        return Outcome.ok("disband.success", "tag", clan.tag());
    }

    public synchronized Outcome adminDisband(String tag) {
        Optional<Clan> found = byTag(tag);
        if (found.isEmpty()) {
            return Outcome.fail("error.clan-not-found", "tag", tag);
        }
        Clan clan = found.get();
        notifier.clan(clan, "disband.notify", Outcome.vars("tag", clan.tag()), null);
        remove(clan);
        return Outcome.ok("admin.disbanded", "tag", clan.tag());
    }

    // ---- membership ----------------------------------------------------------------------

    public synchronized Outcome invite(UUID actor, UUID target, String targetName) {
        Optional<Clan> found = clanOf(actor);
        if (found.isEmpty()) {
            return Outcome.fail("error.not-in-clan");
        }
        Clan clan = found.get();
        if (!roleOf(clan, actor).atLeast(ClanRole.OFFICER)) {
            return Outcome.fail("error.role-too-low");
        }
        if (actor.equals(target)) {
            return Outcome.fail("error.cannot-target-self");
        }
        if (byMember.containsKey(target)) {
            return Outcome.fail("error.target-already-in-clan", "player", targetName);
        }
        if (isFull(clan)) {
            return Outcome.fail("error.clan-full", "max", String.valueOf(settings.maxMembers()));
        }
        Map<UUID, Long> pending = invites.computeIfAbsent(target, k -> new ConcurrentHashMap<>());
        long now = clock.millis();
        Long existing = pending.get(clan.id());
        if (existing != null && existing > now) {
            return Outcome.fail("error.already-invited", "player", targetName);
        }
        pending.put(clan.id(), now + settings.inviteExpiry().toMillis());
        String inviterName = clan.member(actor).map(ClanMember::name).orElse("?");
        notifier.player(target, "invite.received",
                Outcome.vars("tag", clan.tag(), "name", clan.name(), "player", inviterName,
                        "seconds", String.valueOf(settings.inviteExpiry().toSeconds())));
        notifier.clan(clan, "invite.broadcast", Outcome.vars("player", targetName, "actor", inviterName), actor);
        return Outcome.ok("invite.sent", "player", targetName);
    }

    public synchronized Outcome accept(UUID actor, String actorName, @Nullable String tag) {
        if (byMember.containsKey(actor)) {
            return Outcome.fail("error.already-in-clan");
        }
        Optional<Clan> invited = resolveInvite(actor, tag);
        if (invited.isEmpty()) {
            return tag == null && pendingInvites(actor).size() > 1
                    ? Outcome.fail("error.multiple-invites", "tags", tagList(pendingInvites(actor)))
                    : Outcome.fail("error.no-invite");
        }
        Clan clan = invited.get();
        if (isFull(clan)) {
            return Outcome.fail("error.clan-full", "max", String.valueOf(settings.maxMembers()));
        }
        addMember(clan, actor, actorName);
        return Outcome.ok("join.success", "tag", clan.tag(), "name", clan.name());
    }

    public synchronized Outcome deny(UUID actor, @Nullable String tag) {
        Optional<Clan> invited = resolveInvite(actor, tag);
        if (invited.isEmpty()) {
            return tag == null && pendingInvites(actor).size() > 1
                    ? Outcome.fail("error.multiple-invites", "tags", tagList(pendingInvites(actor)))
                    : Outcome.fail("error.no-invite");
        }
        Clan clan = invited.get();
        Map<UUID, Long> pending = invites.get(actor);
        if (pending != null) {
            pending.remove(clan.id());
        }
        return Outcome.ok("invite.denied", "tag", clan.tag());
    }

    public synchronized Outcome join(UUID actor, String actorName, String tag) {
        if (byMember.containsKey(actor)) {
            return Outcome.fail("error.already-in-clan");
        }
        Optional<Clan> found = byTag(tag);
        if (found.isEmpty()) {
            return Outcome.fail("error.clan-not-found", "tag", tag);
        }
        Clan clan = found.get();
        boolean invited = resolveInvite(actor, tag).isPresent();
        if (!clan.open() && !invited) {
            return Outcome.fail("error.clan-not-open", "tag", clan.tag());
        }
        if (isFull(clan)) {
            return Outcome.fail("error.clan-full", "max", String.valueOf(settings.maxMembers()));
        }
        addMember(clan, actor, actorName);
        return Outcome.ok("join.success", "tag", clan.tag(), "name", clan.name());
    }

    public synchronized Outcome leave(UUID actor) {
        Optional<Clan> found = clanOf(actor);
        if (found.isEmpty()) {
            return Outcome.fail("error.not-in-clan");
        }
        Clan clan = found.get();
        if (roleOf(clan, actor) == ClanRole.LEADER) {
            return Outcome.fail("error.leader-cannot-leave");
        }
        String name = clan.member(actor).map(ClanMember::name).orElse("?");
        clan.removeMember(actor);
        byMember.remove(actor);
        persist(clan);
        notifier.clan(clan, "leave.broadcast", Outcome.vars("player", name), null);
        return Outcome.ok("leave.success", "tag", clan.tag());
    }

    public synchronized Outcome kick(UUID actor, String targetName) {
        Optional<Clan> found = clanOf(actor);
        if (found.isEmpty()) {
            return Outcome.fail("error.not-in-clan");
        }
        Clan clan = found.get();
        ClanRole actorRole = roleOf(clan, actor);
        if (!actorRole.atLeast(ClanRole.OFFICER)) {
            return Outcome.fail("error.role-too-low");
        }
        Optional<ClanMember> target = memberByName(clan, targetName);
        if (target.isEmpty()) {
            return Outcome.fail("error.player-not-in-your-clan", "player", targetName);
        }
        ClanMember member = target.get();
        if (member.id().equals(actor)) {
            return Outcome.fail("error.cannot-target-self");
        }
        if (!actorRole.outranks(member.role())) {
            return Outcome.fail("error.cannot-act-on-rank", "player", member.name());
        }
        clan.removeMember(member.id());
        byMember.remove(member.id());
        persist(clan);
        String actorName = clan.member(actor).map(ClanMember::name).orElse("?");
        notifier.player(member.id(), "kick.notify", Outcome.vars("tag", clan.tag(), "actor", actorName));
        notifier.clan(clan, "kick.broadcast", Outcome.vars("player", member.name(), "actor", actorName), actor);
        return Outcome.ok("kick.success", "player", member.name());
    }

    public synchronized Outcome promote(UUID actor, String targetName) {
        return changeRole(actor, targetName, ClanRole.OFFICER, ClanRole.MEMBER, "promote");
    }

    public synchronized Outcome demote(UUID actor, String targetName) {
        return changeRole(actor, targetName, ClanRole.MEMBER, ClanRole.OFFICER, "demote");
    }

    public synchronized Outcome transfer(UUID actor, String targetName) {
        Optional<Clan> found = clanOf(actor);
        if (found.isEmpty()) {
            return Outcome.fail("error.not-in-clan");
        }
        Clan clan = found.get();
        if (roleOf(clan, actor) != ClanRole.LEADER) {
            return Outcome.fail("error.role-too-low");
        }
        Optional<ClanMember> target = memberByName(clan, targetName);
        if (target.isEmpty()) {
            return Outcome.fail("error.player-not-in-your-clan", "player", targetName);
        }
        ClanMember member = target.get();
        if (member.id().equals(actor)) {
            return Outcome.fail("error.cannot-target-self");
        }
        clan.putMember(member.withRole(ClanRole.LEADER));
        clan.member(actor).ifPresent(self -> clan.putMember(self.withRole(ClanRole.OFFICER)));
        persist(clan);
        notifier.clan(clan, "transfer.broadcast", Outcome.vars("player", member.name()), actor);
        return Outcome.ok("transfer.success", "player", member.name());
    }

    // ---- settings ------------------------------------------------------------------------

    public synchronized Outcome setHome(UUID actor, ClanHome home) {
        Optional<Clan> found = officerClan(actor);
        if (found.isEmpty()) {
            return clanOf(actor).isEmpty() ? Outcome.fail("error.not-in-clan") : Outcome.fail("error.role-too-low");
        }
        Clan clan = found.get();
        clan.home(home);
        persist(clan);
        notifier.clan(clan, "home.set-broadcast", Outcome.vars("player", nameOf(clan, actor)), actor);
        return Outcome.ok("home.set");
    }

    public synchronized Outcome deleteHome(UUID actor) {
        Optional<Clan> found = officerClan(actor);
        if (found.isEmpty()) {
            return clanOf(actor).isEmpty() ? Outcome.fail("error.not-in-clan") : Outcome.fail("error.role-too-low");
        }
        Clan clan = found.get();
        if (clan.home().isEmpty()) {
            return Outcome.fail("error.no-home");
        }
        clan.home(null);
        persist(clan);
        return Outcome.ok("home.deleted");
    }

    public synchronized Outcome toggleFriendlyFire(UUID actor) {
        Optional<Clan> found = officerClan(actor);
        if (found.isEmpty()) {
            return clanOf(actor).isEmpty() ? Outcome.fail("error.not-in-clan") : Outcome.fail("error.role-too-low");
        }
        Clan clan = found.get();
        clan.friendlyFire(!clan.friendlyFire());
        persist(clan);
        String key = clan.friendlyFire() ? "ff.enabled" : "ff.disabled";
        notifier.clan(clan, key, Outcome.vars("player", nameOf(clan, actor)), actor);
        return Outcome.ok(key, "player", nameOf(clan, actor));
    }

    public synchronized Outcome toggleOpen(UUID actor) {
        Optional<Clan> found = officerClan(actor);
        if (found.isEmpty()) {
            return clanOf(actor).isEmpty() ? Outcome.fail("error.not-in-clan") : Outcome.fail("error.role-too-low");
        }
        Clan clan = found.get();
        clan.open(!clan.open());
        persist(clan);
        return Outcome.ok(clan.open() ? "open.enabled" : "open.disabled");
    }

    // ---- alliances -----------------------------------------------------------------------

    public synchronized Outcome ally(UUID actor, String tag) {
        Optional<Clan> found = officerClan(actor);
        if (found.isEmpty()) {
            return clanOf(actor).isEmpty() ? Outcome.fail("error.not-in-clan") : Outcome.fail("error.role-too-low");
        }
        Clan clan = found.get();
        Optional<Clan> otherFound = byTag(tag);
        if (otherFound.isEmpty()) {
            return Outcome.fail("error.clan-not-found", "tag", tag);
        }
        Clan other = otherFound.get();
        if (other.id().equals(clan.id())) {
            return Outcome.fail("error.ally-self");
        }
        if (clan.isAlliedWith(other.id())) {
            return Outcome.fail("error.already-allied", "tag", other.tag());
        }
        int max = settings.maxAllies();
        if (max >= 0 && clan.allies().size() >= max) {
            return Outcome.fail("error.ally-limit", "max", String.valueOf(max));
        }
        long now = clock.millis();
        Map<UUID, Long> theirRequestsToUs = allyRequests.getOrDefault(clan.id(), Map.of());
        Long theirs = theirRequestsToUs.get(other.id());
        if (theirs != null && theirs > now) {
            if (max >= 0 && other.allies().size() >= max) {
                return Outcome.fail("error.ally-limit-other", "tag", other.tag(), "max", String.valueOf(max));
            }
            allyRequests.get(clan.id()).remove(other.id());
            clan.addAlly(other.id());
            other.addAlly(clan.id());
            persist(clan);
            persist(other);
            notifier.clan(other, "ally.formed", Outcome.vars("tag", clan.tag()), null);
            notifier.clan(clan, "ally.formed", Outcome.vars("tag", other.tag()), null);
            return Outcome.ok("ally.accepted", "tag", other.tag());
        }
        Map<UUID, Long> ourRequests = allyRequests.computeIfAbsent(other.id(), k -> new ConcurrentHashMap<>());
        Long ours = ourRequests.get(clan.id());
        if (ours != null && ours > now) {
            return Outcome.fail("error.ally-request-pending", "tag", other.tag());
        }
        ourRequests.put(clan.id(), now + settings.inviteExpiry().toMillis());
        notifier.clan(other, "ally.request-received", Outcome.vars("tag", clan.tag()), null);
        return Outcome.ok("ally.requested", "tag", other.tag());
    }

    public synchronized Outcome unally(UUID actor, String tag) {
        Optional<Clan> found = officerClan(actor);
        if (found.isEmpty()) {
            return clanOf(actor).isEmpty() ? Outcome.fail("error.not-in-clan") : Outcome.fail("error.role-too-low");
        }
        Clan clan = found.get();
        Optional<Clan> otherFound = byTag(tag);
        if (otherFound.isEmpty() || !clan.isAlliedWith(otherFound.get().id())) {
            return Outcome.fail("error.not-allied", "tag", tag);
        }
        Clan other = otherFound.get();
        clan.removeAlly(other.id());
        other.removeAlly(clan.id());
        persist(clan);
        persist(other);
        notifier.clan(other, "ally.removed", Outcome.vars("tag", clan.tag()), null);
        notifier.clan(clan, "ally.removed", Outcome.vars("tag", other.tag()), actor);
        return Outcome.ok("ally.removed", "tag", other.tag());
    }

    // ---- combat --------------------------------------------------------------------------

    /** True when the attacker must not be able to hurt the victim. */
    public boolean isProtected(UUID attacker, UUID victim) {
        if (attacker.equals(victim)) {
            return false;
        }
        Clan a = byMember.get(attacker);
        Clan b = byMember.get(victim);
        if (a == null || b == null) {
            return false;
        }
        if (a == b) {
            return !a.friendlyFire();
        }
        return settings.protectAllies() && a.isAlliedWith(b.id());
    }

    public void recordKill(@Nullable UUID killer, UUID victim) {
        Clan victimClan = byMember.get(victim);
        Clan killerClan = killer == null ? null : byMember.get(killer);
        if (killerClan != null && killerClan == victimClan) {
            return; // friendly kills don't count either way
        }
        if (killerClan != null) {
            killerClan.addKill();
            dirty.add(killerClan.id());
        }
        if (victimClan != null) {
            victimClan.addDeath();
            dirty.add(victimClan.id());
        }
    }

    public void updateName(UUID playerId, String name) {
        Clan clan = byMember.get(playerId);
        if (clan == null) {
            return;
        }
        synchronized (this) {
            clan.member(playerId).ifPresent(m -> {
                if (!m.name().equals(name)) {
                    clan.putMember(m.withName(name));
                    persist(clan);
                }
            });
        }
    }

    /**
     * Writes clans whose stats changed since the last flush. Synchronized with the mutating
     * methods: otherwise a snapshot taken here could be queued after a newer save or a
     * disband's delete, and bring back stale members or a disbanded clan on restart.
     */
    public synchronized void flushDirty() {
        for (UUID id : Set.copyOf(dirty)) {
            dirty.remove(id);
            Clan clan = byId.get(id);
            if (clan != null) {
                storage.save(clan);
            }
        }
    }

    public void purgeExpired() {
        long now = clock.millis();
        invites.values().forEach(m -> m.values().removeIf(exp -> exp <= now));
        invites.values().removeIf(Map::isEmpty);
        allyRequests.values().forEach(m -> m.values().removeIf(exp -> exp <= now));
        allyRequests.values().removeIf(Map::isEmpty);
    }

    // ---- helpers -------------------------------------------------------------------------

    public static ClanRole roleOf(Clan clan, UUID playerId) {
        return clan.member(playerId).map(ClanMember::role).orElse(ClanRole.MEMBER);
    }

    public static Optional<ClanMember> memberByName(Clan clan, String name) {
        return clan.members().stream().filter(m -> m.name().equalsIgnoreCase(name)).findFirst();
    }

    private Optional<Clan> officerClan(UUID actor) {
        return clanOf(actor).filter(c -> roleOf(c, actor).atLeast(ClanRole.OFFICER));
    }

    private Outcome changeRole(UUID actor, String targetName, ClanRole to, ClanRole from, String keyBase) {
        Optional<Clan> found = clanOf(actor);
        if (found.isEmpty()) {
            return Outcome.fail("error.not-in-clan");
        }
        Clan clan = found.get();
        if (roleOf(clan, actor) != ClanRole.LEADER) {
            return Outcome.fail("error.role-too-low");
        }
        Optional<ClanMember> target = memberByName(clan, targetName);
        if (target.isEmpty()) {
            return Outcome.fail("error.player-not-in-your-clan", "player", targetName);
        }
        ClanMember member = target.get();
        if (member.id().equals(actor)) {
            return Outcome.fail("error.cannot-target-self");
        }
        if (member.role() != from) {
            return Outcome.fail("error." + keyBase + "-invalid", "player", member.name(), "role", member.role().key());
        }
        clan.putMember(member.withRole(to));
        persist(clan);
        notifier.clan(clan, keyBase + ".broadcast", Outcome.vars("player", member.name(), "role", to.key()), actor);
        return Outcome.ok(keyBase + ".success", "player", member.name(), "role", to.key());
    }

    private void addMember(Clan clan, UUID playerId, String name) {
        clan.putMember(new ClanMember(playerId, name, ClanRole.MEMBER, clock.millis()));
        byMember.put(playerId, clan);
        invites.remove(playerId);
        persist(clan);
        notifier.clan(clan, "join.broadcast", Outcome.vars("player", name), playerId);
    }

    private Optional<Clan> resolveInvite(UUID playerId, @Nullable String tag) {
        List<Clan> pending = pendingInvites(playerId);
        if (tag == null) {
            return pending.size() == 1 ? Optional.of(pending.getFirst()) : Optional.empty();
        }
        String wanted = normalize(tag);
        return pending.stream().filter(c -> normalize(c.tag()).equals(wanted)).findFirst();
    }

    private boolean isFull(Clan clan) {
        int max = settings.maxMembers();
        return max >= 0 && clan.size() >= max;
    }

    private void index(Clan clan) {
        byId.put(clan.id(), clan);
        byTag.put(normalize(clan.tag()), clan);
        for (ClanMember member : clan.members()) {
            byMember.put(member.id(), clan);
        }
    }

    private void remove(Clan clan) {
        byId.remove(clan.id());
        byTag.remove(normalize(clan.tag()));
        for (ClanMember member : clan.members()) {
            byMember.remove(member.id(), clan);
        }
        for (UUID allyId : new HashSet<>(clan.allies())) {
            Clan ally = byId.get(allyId);
            if (ally != null) {
                ally.removeAlly(clan.id());
                persist(ally);
            }
        }
        invites.values().forEach(m -> m.remove(clan.id()));
        allyRequests.remove(clan.id());
        allyRequests.values().forEach(m -> m.remove(clan.id()));
        dirty.remove(clan.id());
        storage.delete(clan.id());
    }

    private void persist(Clan clan) {
        dirty.remove(clan.id());
        storage.save(clan);
    }

    private static String nameOf(Clan clan, UUID playerId) {
        return clan.member(playerId).map(ClanMember::name).orElse("?");
    }

    private static String tagList(List<Clan> clans) {
        return String.join(", ", clans.stream().map(Clan::tag).toList());
    }

    private static String normalize(String tag) {
        return tag.toLowerCase(Locale.ROOT);
    }
}
