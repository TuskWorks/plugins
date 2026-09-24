package io.github.tuskworks.clans.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.tuskworks.clans.model.Clan;
import io.github.tuskworks.clans.model.ClanHome;
import io.github.tuskworks.clans.model.ClanRole;
import io.github.tuskworks.clans.service.TestSupport.MemoryStorage;
import io.github.tuskworks.clans.service.TestSupport.MutableClock;
import io.github.tuskworks.clans.service.TestSupport.RecordingNotifier;
import java.time.Duration;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ClanServiceTest {

    private static final Pattern ALNUM = Pattern.compile("^[A-Za-z0-9]+$");

    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();
    private final UUID carol = UUID.randomUUID();
    private final UUID dave = UUID.randomUUID();

    private MutableClock clock;
    private MemoryStorage storage;
    private RecordingNotifier notifier;
    private ClanService service;

    @BeforeEach
    void setUp() {
        clock = new MutableClock();
        storage = new MemoryStorage();
        notifier = new RecordingNotifier();
        service = new ClanService(ClanSettings.defaults(), storage, notifier, clock);
    }

    private static ClanSettings settings(int maxMembers, int maxAllies, boolean protectAllies) {
        return new ClanSettings(2, 6, ALNUM, 24, maxMembers, maxAllies, Duration.ofMinutes(2), false, protectAllies);
    }

    private Clan createClan(UUID leader, String name, String tag) {
        Outcome outcome = service.create(leader, name, tag, tag + " Clan");
        assertTrue(outcome.success(), outcome.key());
        return service.clanOf(leader).orElseThrow();
    }

    private void addMember(UUID leader, UUID player, String name) {
        assertTrue(service.invite(leader, player, name).success());
        assertTrue(service.accept(player, name, null).success());
    }

    @Test
    void createMakesLeaderAndPersists() {
        Clan clan = createClan(alice, "Alice", "TUSK");
        assertEquals(ClanRole.LEADER, clan.leader().role());
        assertEquals(alice, clan.leader().id());
        assertTrue(storage.saved.contains(clan.id()));
        assertTrue(service.byTag("tusk").isPresent(), "tag lookup is case-insensitive");
    }

    @Test
    void createValidatesInput() {
        assertEquals("error.tag-length", service.create(alice, "Alice", "A", "x").key());
        assertEquals("error.tag-length", service.create(alice, "Alice", "TOOLONG", "x").key());
        assertEquals("error.tag-invalid", service.create(alice, "Alice", "<b>", "x").key());
        assertEquals("error.name-length", service.create(alice, "Alice", "OK", " ").key());
        createClan(alice, "Alice", "TUSK");
        assertEquals("error.tag-taken", service.create(bob, "Bob", "tusk", "x").key());
        assertEquals("error.already-in-clan", service.create(alice, "Alice", "NEW", "x").key());
    }

    @Test
    void validateCreateChecksWithoutCreating() {
        assertTrue(service.validateCreate(alice, "TUSK", "Tusk Clan").isEmpty());
        assertTrue(service.clanOf(alice).isEmpty(), "validation alone must not create anything");
        assertTrue(storage.saved.isEmpty());
        assertEquals("error.tag-invalid", service.validateCreate(alice, "<b>", "x").orElseThrow().key());
        createClan(alice, "Alice", "TUSK");
        assertEquals("error.tag-taken", service.validateCreate(bob, "tusk", "x").orElseThrow().key());
        assertEquals("error.already-in-clan", service.validateCreate(alice, "NEW", "x").orElseThrow().key());
    }

    @Test
    void customTagPatternIsRespected() {
        service.settings(new ClanSettings(2, 6, Pattern.compile("^[A-Z]+$"), 24, 20, 3,
                Duration.ofMinutes(2), false, true));
        assertEquals("error.tag-invalid", service.create(alice, "Alice", "tusk", "x").key());
        assertTrue(service.create(alice, "Alice", "TUSK", "x").success());
    }

    @Test
    void inviteAcceptFlow() {
        Clan clan = createClan(alice, "Alice", "TUSK");
        assertTrue(service.invite(alice, bob, "Bob").success());
        assertTrue(notifier.received(bob, "invite.received"));
        assertEquals(1, service.pendingInvites(bob).size());
        assertEquals("error.already-invited", service.invite(alice, bob, "Bob").key());

        assertTrue(service.accept(bob, "Bob", null).success());
        assertEquals(clan, service.clanOf(bob).orElseThrow());
        assertEquals(ClanRole.MEMBER, ClanService.roleOf(clan, bob));
        assertTrue(notifier.received(alice, "join.broadcast"));
        assertTrue(service.pendingInvites(bob).isEmpty());
    }

    @Test
    void invitesExpire() {
        createClan(alice, "Alice", "TUSK");
        service.invite(alice, bob, "Bob");
        clock.advance(Duration.ofMinutes(3));
        assertEquals("error.no-invite", service.accept(bob, "Bob", null).key());
        assertTrue(service.invite(alice, bob, "Bob").success(), "expired invites can be re-sent");
    }

    @Test
    void acceptNeedsTagWhenSeveralInvites() {
        createClan(alice, "Alice", "TUSK");
        createClan(carol, "Carol", "MAMO");
        service.invite(alice, bob, "Bob");
        service.invite(carol, bob, "Bob");
        assertEquals("error.multiple-invites", service.accept(bob, "Bob", null).key());
        assertTrue(service.accept(bob, "Bob", "mamo").success());
        assertEquals("MAMO", service.clanOf(bob).orElseThrow().tag());
    }

    @Test
    void denyRemovesInvite() {
        createClan(alice, "Alice", "TUSK");
        service.invite(alice, bob, "Bob");
        assertTrue(service.deny(bob, null).success());
        assertTrue(service.pendingInvites(bob).isEmpty());
    }

    @Test
    void membersCannotInvite() {
        createClan(alice, "Alice", "TUSK");
        addMember(alice, bob, "Bob");
        assertEquals("error.role-too-low", service.invite(bob, carol, "Carol").key());
    }

    @Test
    void cannotInviteSomeoneInAClan() {
        createClan(alice, "Alice", "TUSK");
        createClan(bob, "Bob", "MAMO");
        assertEquals("error.target-already-in-clan", service.invite(alice, bob, "Bob").key());
    }

    @Test
    void clanSizeLimit() {
        service.settings(settings(2, 3, true));
        createClan(alice, "Alice", "TUSK");
        addMember(alice, bob, "Bob");
        assertEquals("error.clan-full", service.invite(alice, carol, "Carol").key());
    }

    @Test
    void joinRequiresOpenClanOrInvite() {
        createClan(alice, "Alice", "TUSK");
        assertEquals("error.clan-not-open", service.join(bob, "Bob", "TUSK").key());
        assertTrue(service.toggleOpen(alice).success());
        assertTrue(service.join(bob, "Bob", "tusk").success());
    }

    @Test
    void leaderCannotLeaveButMembersCan() {
        createClan(alice, "Alice", "TUSK");
        addMember(alice, bob, "Bob");
        assertEquals("error.leader-cannot-leave", service.leave(alice).key());
        assertTrue(service.leave(bob).success());
        assertTrue(service.clanOf(bob).isEmpty());
        assertTrue(notifier.received(alice, "leave.broadcast"));
    }

    @Test
    void kickRespectsRanks() {
        Clan clan = createClan(alice, "Alice", "TUSK");
        addMember(alice, bob, "Bob");
        addMember(alice, carol, "Carol");
        assertTrue(service.promote(alice, "bob").success());
        assertEquals(ClanRole.OFFICER, ClanService.roleOf(clan, bob));

        assertEquals("error.role-too-low", service.kick(carol, "Bob").key());
        assertEquals("error.cannot-act-on-rank", service.kick(bob, "Alice").key());
        assertTrue(service.kick(bob, "carol").success());
        assertTrue(service.clanOf(carol).isEmpty());
        assertTrue(notifier.received(carol, "kick.notify"));
        assertEquals("error.player-not-in-your-clan", service.kick(bob, "Nobody").key());
    }

    @Test
    void promoteAndDemoteAreLeaderOnly() {
        Clan clan = createClan(alice, "Alice", "TUSK");
        addMember(alice, bob, "Bob");
        addMember(alice, carol, "Carol");
        service.promote(alice, "Bob");
        assertEquals("error.role-too-low", service.promote(bob, "Carol").key());
        assertEquals("error.promote-invalid", service.promote(alice, "Bob").key());
        assertTrue(service.demote(alice, "Bob").success());
        assertEquals(ClanRole.MEMBER, ClanService.roleOf(clan, bob));
        assertEquals("error.demote-invalid", service.demote(alice, "Bob").key());
    }

    @Test
    void transferSwapsLeader() {
        Clan clan = createClan(alice, "Alice", "TUSK");
        addMember(alice, bob, "Bob");
        assertTrue(service.transfer(alice, "Bob").success());
        assertEquals(bob, clan.leader().id());
        assertEquals(ClanRole.OFFICER, ClanService.roleOf(clan, alice));
        assertTrue(service.leave(alice).success());
    }

    @Test
    void disbandCleansUp() {
        Clan tusk = createClan(alice, "Alice", "TUSK");
        Clan mamo = createClan(carol, "Carol", "MAMO");
        addMember(alice, bob, "Bob");
        service.ally(alice, "MAMO");
        service.ally(carol, "TUSK");
        assertTrue(mamo.isAlliedWith(tusk.id()));
        service.invite(alice, dave, "Dave");

        assertEquals("error.role-too-low", service.disband(bob).key());
        assertTrue(service.disband(alice).success());
        assertTrue(service.clanOf(alice).isEmpty());
        assertTrue(service.clanOf(bob).isEmpty());
        assertTrue(service.byTag("TUSK").isEmpty());
        assertFalse(mamo.isAlliedWith(tusk.id()));
        assertTrue(service.pendingInvites(dave).isEmpty());
        assertTrue(storage.deleted.contains(tusk.id()));
        assertTrue(notifier.received(bob, "disband.notify"));
        assertTrue(service.create(dave, "Dave", "TUSK", "Reborn").success(), "tag is free again");
    }

    @Test
    void allianceNeedsBothSides() {
        Clan tusk = createClan(alice, "Alice", "TUSK");
        Clan mamo = createClan(carol, "Carol", "MAMO");
        assertEquals("ally.requested", service.ally(alice, "MAMO").key());
        assertFalse(tusk.isAlliedWith(mamo.id()));
        assertTrue(notifier.received(carol, "ally.request-received"));
        assertEquals("error.ally-request-pending", service.ally(alice, "MAMO").key());

        assertEquals("ally.accepted", service.ally(carol, "tusk").key());
        assertTrue(tusk.isAlliedWith(mamo.id()));
        assertTrue(mamo.isAlliedWith(tusk.id()));
        assertEquals("error.already-allied", service.ally(alice, "MAMO").key());
        assertEquals("error.ally-self", service.ally(alice, "TUSK").key());

        assertTrue(service.unally(carol, "TUSK").success());
        assertFalse(tusk.isAlliedWith(mamo.id()));
        assertEquals("error.not-allied", service.unally(alice, "MAMO").key());
    }

    @Test
    void allyLimit() {
        service.settings(settings(20, 1, true));
        createClan(alice, "Alice", "TUSK");
        createClan(bob, "Bob", "MAMO");
        createClan(carol, "Carol", "WOOL");
        service.ally(alice, "MAMO");
        service.ally(bob, "TUSK");
        assertEquals("error.ally-limit", service.ally(alice, "WOOL").key());
        service.ally(carol, "TUSK");
        assertEquals("error.ally-limit", service.ally(alice, "WOOL").key());
    }

    @Test
    void friendlyFireProtection() {
        Clan tusk = createClan(alice, "Alice", "TUSK");
        addMember(alice, bob, "Bob");
        createClan(carol, "Carol", "MAMO");

        assertTrue(service.isProtected(alice, bob));
        assertFalse(service.isProtected(alice, carol));
        assertFalse(service.isProtected(alice, dave), "clanless players are fair game");
        assertFalse(service.isProtected(alice, alice));

        service.toggleFriendlyFire(alice);
        assertTrue(tusk.friendlyFire());
        assertFalse(service.isProtected(alice, bob));
        service.toggleFriendlyFire(alice);

        service.ally(alice, "MAMO");
        service.ally(carol, "TUSK");
        assertTrue(service.isProtected(alice, carol));
        service.settings(settings(20, 3, false));
        assertFalse(service.isProtected(alice, carol));
    }

    @Test
    void killStatsIgnoreFriendlyKills() {
        Clan tusk = createClan(alice, "Alice", "TUSK");
        addMember(alice, bob, "Bob");
        Clan mamo = createClan(carol, "Carol", "MAMO");

        service.recordKill(alice, carol);
        service.recordKill(alice, bob);
        service.recordKill(null, alice);
        service.recordKill(dave, carol);

        assertEquals(1, tusk.kills());
        assertEquals(1, tusk.deaths());
        assertEquals(0, mamo.kills());
        assertEquals(2, mamo.deaths());
        assertEquals(tusk, service.top(10).getFirst());

        storage.saved.clear();
        service.flushDirty();
        assertTrue(storage.saved.contains(tusk.id()));
        assertTrue(storage.saved.contains(mamo.id()));
    }

    @Test
    void homeRequiresOfficer() {
        Clan clan = createClan(alice, "Alice", "TUSK");
        addMember(alice, bob, "Bob");
        ClanHome home = new ClanHome("world", 1, 64, 1, 0, 0);
        assertEquals("error.role-too-low", service.setHome(bob, home).key());
        assertTrue(service.setHome(alice, home).success());
        assertEquals(home, clan.home().orElseThrow());
        assertTrue(service.deleteHome(alice).success());
        assertEquals("error.no-home", service.deleteHome(alice).key());
    }

    @Test
    void nameUpdatesAreStored() {
        Clan clan = createClan(alice, "Alice", "TUSK");
        service.updateName(alice, "Alicia");
        assertEquals("Alicia", clan.leader().name());
    }
}
