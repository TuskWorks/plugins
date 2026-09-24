package io.github.tuskworks.orders.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.tuskworks.orders.model.Order;
import io.github.tuskworks.orders.model.OrderStatus;
import io.github.tuskworks.orders.money.Money;
import io.github.tuskworks.orders.service.TestSupport.FakeBank;
import io.github.tuskworks.orders.service.TestSupport.MemoryStorage;
import io.github.tuskworks.orders.service.TestSupport.MutableClock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OrderServiceTest {

    private static final UUID BUYER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SELLER = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final String DIAMOND = "minecraft:diamond";

    private FakeBank bank;
    private MemoryStorage storage;
    private MutableClock clock;
    private OrderService service;

    @BeforeEach
    void setUp() {
        bank = new FakeBank();
        storage = new MemoryStorage();
        clock = new MutableClock();
        service = newService(settings(0, 0, Duration.ofDays(7)));
        bank.give(BUYER, 100_000_00);
    }

    private OrderService newService(OrderSettings settings) {
        return new OrderService(settings, new Money("$"), storage, bank, clock, Logger.getLogger("test"));
    }

    private static OrderSettings settings(double fee, double tax, Duration expiry) {
        return new OrderSettings(10_000, 1, 1_000_000_00, expiry, fee, tax);
    }

    private int createDiamonds(int amount, long priceEach) {
        Outcome outcome = service.create(BUYER, "Buyer", DIAMOND, amount, priceEach, 5);
        assertTrue(outcome.success(), outcome.key());
        return Integer.parseInt(outcome.vars().get("id"));
    }

    @Test
    void createTakesFullValueIntoEscrow() {
        int id = createDiamonds(64, 50_00);

        assertEquals(100_000_00 - 64 * 50_00, bank.balance(BUYER));
        Order order = service.get(id).orElseThrow();
        assertEquals(64 * 50_00, order.escrow());
        assertEquals(order, storage.saved.get(id));
    }

    @Test
    void createChargesFeeOnTop() {
        service = newService(settings(5, 0, Duration.ZERO));
        Outcome outcome = service.create(BUYER, "Buyer", DIAMOND, 10, 100_00, 5);

        assertTrue(outcome.success());
        assertEquals(100_000_00 - 1_050_00, bank.balance(BUYER));
        assertEquals("$1,050", outcome.vars().get("total"));
        assertEquals(0, service.get(1).orElseThrow().expiresAt());
    }

    @Test
    void createRejectsWhenBrokeWithoutTouchingBalance() {
        Outcome outcome = service.create(SELLER, "Seller", DIAMOND, 1, 5_00, 5);

        assertFalse(outcome.success());
        assertEquals("create.no-money", outcome.key());
        assertTrue(service.byOwner(SELLER).isEmpty());
    }

    @Test
    void createEnforcesLimitsAndBounds() {
        assertEquals("create.invalid-amount", service.create(BUYER, "Buyer", DIAMOND, 0, 1_00, 5).key());
        assertEquals("create.invalid-amount", service.create(BUYER, "Buyer", DIAMOND, 10_001, 1_00, 5).key());
        assertEquals("create.price-too-high", service.create(BUYER, "Buyer", DIAMOND, 1, 1_000_000_01, 5).key());
        assertEquals("create.price-too-low", service.create(BUYER, "Buyer", DIAMOND, 1, 0, 5).key());

        createDiamonds(1, 1_00);
        assertEquals("create.limit-reached", service.create(BUYER, "Buyer", DIAMOND, 1, 1_00, 1).key());
    }

    @Test
    void createRejectsTotalsThatWouldOverflow() {
        service = newService(new OrderSettings(Integer.MAX_VALUE, 1, Long.MAX_VALUE, Duration.ZERO, 0, 0));
        Outcome outcome = service.create(BUYER, "Buyer", DIAMOND, Integer.MAX_VALUE, Long.MAX_VALUE / 2, 5);

        assertEquals("create.too-expensive", outcome.key());
        assertEquals(100_000_00, bank.balance(BUYER));
    }

    @Test
    void deliverPaysPerItemAndCapsAtRemaining() {
        int id = createDiamonds(10, 50_00);

        OrderService.Delivery first = service.deliver(id, SELLER, 4);
        assertEquals(4, first.accepted());
        assertEquals(200_00, bank.balance(SELLER));

        OrderService.Delivery second = service.deliver(id, SELLER, 64);
        assertEquals(6, second.accepted());
        assertEquals(500_00, bank.balance(SELLER));

        Order order = service.get(id).orElseThrow();
        assertEquals(OrderStatus.COMPLETED, order.status());
        assertEquals(0, order.escrow());
        assertEquals("deliver.unavailable", service.deliver(id, SELLER, 1).outcome().key());
    }

    @Test
    void deliverAppliesTax() {
        service = newService(settings(0, 10, Duration.ZERO));
        int id = createDiamonds(3, 33_33);

        service.deliver(id, SELLER, 3);

        // 99.99 payout, 10% tax rounded down to 9.99
        assertEquals(90_00, bank.balance(SELLER));
    }

    @Test
    void cannotDeliverToOwnOrder() {
        int id = createDiamonds(10, 50_00);

        OrderService.Delivery delivery = service.deliver(id, BUYER, 5);

        assertEquals("deliver.own-order", delivery.outcome().key());
        assertEquals(0, delivery.accepted());
    }

    @Test
    void failedPayoutRollsBackDelivery() {
        int id = createDiamonds(10, 50_00);
        bank.failDeposits = true;

        OrderService.Delivery delivery = service.deliver(id, SELLER, 10);

        assertEquals(0, delivery.accepted());
        Order order = service.get(id).orElseThrow();
        assertEquals(0, order.delivered());
        assertEquals(OrderStatus.ACTIVE, order.status());
        assertEquals(0, bank.balance(SELLER));
    }

    @Test
    void itemsAwaitingPayoutCannotBeCollected() {
        int id = createDiamonds(10, 50_00);
        service.deliver(id, SELLER, 4);
        int[] collectedDuringPayout = {-1};
        bank.beforeDeposit = () -> collectedDuringPayout[0] = service.collect(id, BUYER, 100);

        service.deliver(id, SELLER, 6);

        // Only the 4 already paid for were collectable while the second payout was pending
        assertEquals(4, collectedDuringPayout[0]);
        assertEquals(6, service.collect(id, BUYER, 100));
    }

    @Test
    void cancelRefundsOnlyTheUnfilledPart() {
        int id = createDiamonds(10, 50_00);
        service.deliver(id, SELLER, 4);

        Outcome outcome = service.cancel(id, BUYER, false);

        assertTrue(outcome.success());
        assertEquals(100_000_00 - 4 * 50_00, bank.balance(BUYER));
        Order order = service.get(id).orElseThrow();
        assertEquals(OrderStatus.CANCELLED, order.status());
        assertEquals(4, order.uncollected());
        assertEquals("deliver.unavailable", service.deliver(id, SELLER, 1).outcome().key());
    }

    @Test
    void cancelWithNothingToCollectArchivesTheOrder() {
        int id = createDiamonds(10, 50_00);

        service.cancel(id, BUYER, false);

        assertTrue(service.get(id).isEmpty());
        assertFalse(storage.saved.containsKey(id));
        assertEquals(100_000_00, bank.balance(BUYER));
    }

    @Test
    void failedRefundKeepsOrderOpen() {
        int id = createDiamonds(10, 50_00);
        bank.failDeposits = true;

        Outcome outcome = service.cancel(id, BUYER, false);

        assertEquals("cancel.refund-failed", outcome.key());
        assertEquals(OrderStatus.ACTIVE, service.get(id).orElseThrow().status());
        assertEquals(storage.saved.get(id), service.get(id).orElseThrow());
    }

    @Test
    void onlyOwnerOrAdminCanCancel() {
        int id = createDiamonds(10, 50_00);

        assertEquals("cancel.not-owner", service.cancel(id, SELLER, false).key());
        assertTrue(service.cancel(id, SELLER, true).success());
        assertEquals("cancel.not-found", service.cancel(id, SELLER, true).key());
    }

    @Test
    void collectRespectsSpaceAndArchivesWhenDone() {
        int id = createDiamonds(100, 1_00);
        service.deliver(id, SELLER, 100);

        assertEquals(64, service.collect(id, BUYER, 64));
        assertEquals(0, service.collect(id, SELLER, 64));
        assertEquals(36, service.collect(id, BUYER, 64));
        assertTrue(service.get(id).isEmpty());
        assertFalse(storage.saved.containsKey(id));
    }

    @Test
    void expiryRefundsAndRetriesAfterFailure() {
        int id = createDiamonds(10, 50_00);
        service.deliver(id, SELLER, 2);
        clock.advance(Duration.ofDays(7));
        bank.failDeposits = true;

        assertTrue(service.expireOverdue().isEmpty());
        assertEquals(OrderStatus.ACTIVE, service.get(id).orElseThrow().status());

        bank.failDeposits = false;
        List<Order> expired = service.expireOverdue();

        assertEquals(1, expired.size());
        assertEquals(OrderStatus.EXPIRED, expired.getFirst().status());
        assertEquals(100_000_00 - 2 * 50_00, bank.balance(BUYER));
        assertEquals(2, service.get(id).orElseThrow().uncollected());
    }

    // ---- a failed payout rolled back while the order is being closed ----------------------------

    /**
     * Replays this interleaving: a delivery is recorded and its payout starts, the buyer closes
     * the order (cancel or expiry), the payout fails and the delivery is rolled back, and only then
     * does the close's refund finish, succeeding or failing as given.
     */
    private void closeDuringFailedPayout(int id, int offered, boolean closeRefundSucceeds, Runnable close)
            throws InterruptedException {
        CountDownLatch payoutStarted = new CountDownLatch(1);
        CountDownLatch closeMarked = new CountDownLatch(1);
        CountDownLatch rollbackDone = new CountDownLatch(1);
        bank.depositRule = (player, cents) -> {
            if (player.equals(SELLER)) {
                payoutStarted.countDown();
                await(closeMarked);
                return false; // the payout fails once the close is under way
            }
            if (Thread.currentThread().getName().equals("closer")) {
                closeMarked.countDown();
                await(rollbackDone);
                return closeRefundSucceeds;
            }
            return true;
        };
        Thread deliverer = new Thread(() -> {
            assertEquals(0, service.deliver(id, SELLER, offered).accepted());
            rollbackDone.countDown();
        }, "deliverer");
        Thread closer = new Thread(() -> {
            await(payoutStarted);
            close.run();
        }, "closer");
        deliverer.start();
        closer.start();
        deliverer.join(5_000);
        closer.join(5_000);
        assertFalse(deliverer.isAlive() || closer.isAlive(), "interleaving got stuck");
        bank.depositRule = (player, cents) -> true;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("interleaving timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    @Test
    void rollbackDuringFailedCancelRefundCreatesNoMoney() throws InterruptedException {
        int id = createDiamonds(100, 10_00);
        service.deliver(id, SELLER, 10); // paid for and waiting to be collected
        Outcome[] cancel = new Outcome[1];

        closeDuringFailedPayout(id, 64, false, () -> cancel[0] = service.cancel(id, BUYER, false));

        assertEquals("cancel.refund-failed", cancel[0].key());
        Order order = service.get(id).orElseThrow();
        assertEquals(OrderStatus.ACTIVE, order.status());
        assertEquals(10, order.delivered());
        // Nothing was refunded, so the full unfilled part is still in escrow and nowhere else
        assertEquals(100_000_00 - 100 * 10_00, bank.balance(BUYER));
        assertEquals(90 * 10_00, order.escrow());

        assertTrue(service.cancel(id, BUYER, false).success());
        assertEquals(100_000_00 - 10 * 10_00, bank.balance(BUYER), "buyer pays exactly for the 10 delivered");
        assertEquals(10 * 10_00, bank.balance(SELLER));
    }

    @Test
    void rollbackDuringFailedCancelRefundKeepsTheOrder() throws InterruptedException {
        int id = createDiamonds(100, 10_00);
        Outcome[] cancel = new Outcome[1];

        closeDuringFailedPayout(id, 64, false, () -> cancel[0] = service.cancel(id, BUYER, false));

        assertEquals("cancel.refund-failed", cancel[0].key());
        Order order = service.get(id).orElseThrow(); // must not be archived while its refund was pending
        assertEquals(OrderStatus.ACTIVE, order.status());
        assertEquals(100 * 10_00, order.escrow());
        assertEquals(100_000_00 - 100 * 10_00, bank.balance(BUYER));
        assertEquals(order, storage.saved.get(id));
    }

    @Test
    void rollbackDuringSuccessfulCancelRefundsEverything() throws InterruptedException {
        int id = createDiamonds(100, 10_00);
        Outcome[] cancel = new Outcome[1];

        closeDuringFailedPayout(id, 64, true, () -> cancel[0] = service.cancel(id, BUYER, false));

        assertTrue(cancel[0].success());
        assertEquals(100_000_00, bank.balance(BUYER), "the 36 refunded by the cancel plus the 64 rolled back");
        assertTrue(service.get(id).isEmpty(), "nothing left to collect, so the order is archived");
        assertFalse(storage.saved.containsKey(id));
    }

    @Test
    void rollbackDuringFailedExpiryRefundCreatesNoMoney() throws InterruptedException {
        int id = createDiamonds(100, 10_00);
        service.deliver(id, SELLER, 10);
        clock.advance(Duration.ofDays(7));
        List<List<Order>> expired = new ArrayList<>();

        closeDuringFailedPayout(id, 64, false, () -> expired.add(service.expireOverdue()));

        assertTrue(expired.getFirst().isEmpty());
        Order order = service.get(id).orElseThrow();
        assertEquals(OrderStatus.ACTIVE, order.status(), "left open for the next sweep");
        assertEquals(90 * 10_00, order.escrow());
        assertEquals(100_000_00 - 100 * 10_00, bank.balance(BUYER));

        assertEquals(1, service.expireOverdue().size());
        assertEquals(100_000_00 - 10 * 10_00, bank.balance(BUYER));
    }

    @Test
    void collectingWhileACancelRefundIsPendingKeepsTheOrder() {
        int id = createDiamonds(10, 50_00);
        service.deliver(id, SELLER, 4);
        bank.beforeDeposit = () -> service.collect(id, BUYER, 100); // empties it mid-refund
        bank.failDeposits = true;

        Outcome outcome = service.cancel(id, BUYER, false);

        assertEquals("cancel.refund-failed", outcome.key());
        Order order = service.get(id).orElseThrow();
        assertEquals(OrderStatus.ACTIVE, order.status());
        assertEquals(6 * 50_00, order.escrow());
    }

    @Test
    void notExpiredBeforeDeadline() {
        createDiamonds(10, 50_00);
        clock.advance(Duration.ofDays(7).minusSeconds(1));

        assertTrue(service.expireOverdue().isEmpty());
    }

    @Test
    void activeListingFiltersAndSorts() {
        int cheap = createDiamonds(10, 10_00);
        int pricey = createDiamonds(10, 90_00);
        Outcome logs = service.create(BUYER, "Buyer", "minecraft:oak_log", 64, 50_00, 5);
        int log = Integer.parseInt(logs.vars().get("id"));

        assertEquals(List.of(pricey, log, cheap), ids(service.active(OrderSort.PRICE, "")));
        assertEquals(List.of(log), ids(service.active(OrderSort.PRICE, "oak log")));
        assertEquals(List.of(log), ids(service.active(OrderSort.PRICE, "OAK_LOG")));
        assertEquals(List.of(log, pricey, cheap), ids(service.active(OrderSort.PAYOUT, "")));
    }

    @Test
    void loadContinuesIdSequence() {
        int id = createDiamonds(1, 1_00);
        OrderService reloaded = newService(settings(0, 0, Duration.ZERO));
        reloaded.load(storage.loadAll(), storage.loadNextId());

        Outcome outcome = reloaded.create(BUYER, "Buyer", DIAMOND, 1, 1_00, 5);

        assertEquals(String.valueOf(id + 1), outcome.vars().get("id"));
    }

    @Test
    void idsOfDeletedOrdersAreNotReusedAfterRestart() {
        int id = createDiamonds(1, 1_00);
        assertTrue(service.cancel(id, BUYER, false).success());
        assertTrue(storage.saved.isEmpty(), "a cancelled order with nothing to collect is deleted");

        OrderService reloaded = newService(settings(0, 0, Duration.ZERO));
        reloaded.load(storage.loadAll(), storage.loadNextId());
        Outcome outcome = reloaded.create(BUYER, "Buyer", DIAMOND, 1, 1_00, 5);

        assertEquals(String.valueOf(id + 1), outcome.vars().get("id"));
    }

    @Test
    void loadTrustsOrderIdsOverAStaleSavedSequence() {
        int id = createDiamonds(1, 1_00);
        OrderService reloaded = newService(settings(0, 0, Duration.ZERO));
        // e.g. the server stopped between writing the order and the sequence
        reloaded.load(storage.loadAll(), 1);

        Outcome outcome = reloaded.create(BUYER, "Buyer", DIAMOND, 1, 1_00, 5);

        assertEquals(String.valueOf(id + 1), outcome.vars().get("id"));
    }

    @Test
    void renameUpdatesOwnerName() {
        int id = createDiamonds(1, 1_00);

        service.updateOwnerName(BUYER, "NewName");

        assertEquals("NewName", service.get(id).orElseThrow().ownerName());
        assertEquals("NewName", storage.saved.get(id).ownerName());
    }

    private static List<Integer> ids(List<Order> orders) {
        return orders.stream().map(Order::id).toList();
    }
}
