package io.meshrelay.prototype;

import io.meshrelay.prototype.crypto.HybridCryptoService;
import io.meshrelay.prototype.crypto.ServerKeyHolder;
import io.meshrelay.prototype.model.MeshPacket;
import io.meshrelay.prototype.model.PaymentInstruction;
import io.meshrelay.prototype.model.AccountRepository;
import io.meshrelay.prototype.service.BridgeIngestionService;
import io.meshrelay.prototype.service.DemoService;
import io.meshrelay.prototype.service.IdempotencyService;
import io.meshrelay.prototype.service.MeshSimulatorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The killer test: simulates the "three bridges deliver at the same instant"
 * scenario the user explicitly cared about.
 */
@SpringBootTest
class IdempotencyConcurrencyTest {

    @Autowired
    private DemoService demoService;
    @Autowired
    private BridgeIngestionService bridge;
    @Autowired
    private IdempotencyService idempotency;
    @Autowired
    private AccountRepository accounts;
    @Autowired
    private HybridCryptoService crypto;
    @Autowired
    private ServerKeyHolder serverKey;
    @Autowired
    private MeshSimulatorService mesh;

    @BeforeEach
    void clear() {
        idempotency.clear();
        mesh.setOnlineDeviceCount(0);
        mesh.resetMesh();
        // Restore deterministic fictional balances so tests are independent of execution order.
        restoreBalance("alice@mesh", "5000.00");
        restoreBalance("bob@mesh", "2750.00");
        restoreBalance("carol@mesh", "4125.50");
        restoreBalance("dave@mesh", "1800.00");
        restoreBalance("erin@mesh", "6350.75");
    }

    /**
     * Resets one demo account to its known test balance.
     */
    private void restoreBalance(String vpa, String balance) {
        accounts.findById(vpa).ifPresent(account -> {
            account.setBalance(new BigDecimal(balance));
            accounts.save(account);
        });
    }

    @Test
    void fiveDemoAccountsExistAndFiveDevicesStartOffline() {
        assertTrue(accounts.existsById("alice@mesh"));
        assertTrue(accounts.existsById("bob@mesh"));
        assertTrue(accounts.existsById("carol@mesh"));
        assertTrue(accounts.existsById("dave@mesh"));
        assertTrue(accounts.existsById("erin@mesh"));
        assertEquals(5, accounts.count(), "legacy demo accounts should be migrated, not duplicated");
        assertEquals(5, mesh.getDevices().size());
        assertTrue(mesh.getDevices().stream().noneMatch(device -> device.hasInternet()),
                "all demo devices must start offline");
    }

    @Test
    void connectivitySelectorSetsExactOnlineAndOfflineCounts() {
        mesh.setOnlineDeviceCount(3);
        assertEquals(3, mesh.getDevices().stream().filter(device -> device.hasInternet()).count());
        assertEquals(2, mesh.getDevices().stream().filter(device -> !device.hasInternet()).count());
        mesh.setOnlineDeviceCount(0);
    }

    @Test
    void processedPacketRemainsDuplicateAfterInMemoryCacheIsCleared() throws Exception {
        MeshPacket packet = demoService.createPacket(
                "alice@mesh", "bob@mesh", new BigDecimal("25.00"), "demo-only", 5);
        BridgeIngestionService.IngestResult first = bridge.ingest(packet, "bridge-first", 1);
        assertEquals("SETTLED", first.outcome());

        idempotency.clear(); // Simulate process-local cache loss/reset.
        BridgeIngestionService.IngestResult retry = bridge.ingest(packet, "bridge-retry", 1);
        assertEquals("DUPLICATE_DROPPED", retry.outcome());
    }

    @Test
    void singlePacketDeliveredByThreeBridgesSettlesExactlyOnce() throws Exception {
        // Capture starting balances
        BigDecimal aliceBefore = accounts.findById("alice@mesh").orElseThrow().getBalance();
        BigDecimal bobBefore = accounts.findById("bob@mesh").orElseThrow().getBalance();

        // One packet, but we'll deliver it from 3 "bridges" simultaneously
        MeshPacket packet = demoService.createPacket(
                "alice@mesh", "bob@mesh", new BigDecimal("100.00"), "1234", 5);

        ExecutorService pool = Executors.newFixedThreadPool(3);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger settled = new AtomicInteger();
        AtomicInteger duplicates = new AtomicInteger();

        Future<?>[] futures = new Future[3];
        for (int i = 0; i < 3; i++) {
            final String node = "bridge-" + i;
            futures[i] = pool.submit(() -> {
                try {
                    start.await();
                    BridgeIngestionService.IngestResult r = bridge.ingest(packet, node, 3);
                    if ("SETTLED".equals(r.outcome())) settled.incrementAndGet();
                    else if ("DUPLICATE_DROPPED".equals(r.outcome())) duplicates.incrementAndGet();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }

        start.countDown(); // release all 3 threads at once
        for (Future<?> f : futures) f.get(5, TimeUnit.SECONDS);
        pool.shutdown();

        assertEquals(1, settled.get(), "exactly one bridge should settle");
        assertEquals(2, duplicates.get(), "the other two should be duplicates");

        // Balance moved exactly once
        BigDecimal aliceAfter = accounts.findById("alice@mesh").orElseThrow().getBalance();
        BigDecimal bobAfter = accounts.findById("bob@mesh").orElseThrow().getBalance();
        assertEquals(aliceBefore.subtract(new BigDecimal("100.00")), aliceAfter);
        assertEquals(bobBefore.add(new BigDecimal("100.00")), bobAfter);
    }

    @Test
    void tamperedCiphertextIsRejected() throws Exception {
        MeshPacket packet = demoService.createPacket(
                "alice@mesh", "bob@mesh", new BigDecimal("50.00"), "1234", 5);

        // Flip a byte in the middle of the ciphertext
        char[] chars = packet.getCiphertext().toCharArray();
        chars[chars.length / 2] = chars[chars.length / 2] == 'A' ? 'B' : 'A';
        packet.setCiphertext(new String(chars));

        BridgeIngestionService.IngestResult r = bridge.ingest(packet, "bridge-x", 1);
        assertEquals("INVALID", r.outcome());
    }

    @Test
    void competingDifferentPaymentsCannotOverspendTheSameAccount() throws Exception {
        MeshPacket first = demoService.createPacket(
                "alice@mesh", "bob@mesh", new BigDecimal("4000.00"), "demo-only", 5);
        MeshPacket second = demoService.createPacket(
                "alice@mesh", "carol@mesh", new BigDecimal("4000.00"), "demo-only", 5);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<BridgeIngestionService.IngestResult> one = pool.submit(() -> {
            start.await();
            return bridge.ingest(first, "bridge-concurrent-a", 1);
        });
        Future<BridgeIngestionService.IngestResult> two = pool.submit(() -> {
            start.await();
            return bridge.ingest(second, "bridge-concurrent-b", 1);
        });
        start.countDown();
        BridgeIngestionService.IngestResult resultOne = one.get(10, TimeUnit.SECONDS);
        BridgeIngestionService.IngestResult resultTwo = two.get(10, TimeUnit.SECONDS);
        pool.shutdownNow();

        long settled = java.util.stream.Stream.of(resultOne, resultTwo)
                .filter(result -> "SETTLED".equals(result.outcome())).count();
        long rejected = java.util.stream.Stream.of(resultOne, resultTwo)
                .filter(result -> "REJECTED".equals(result.outcome())).count();
        assertEquals(1, settled, "only one competing transfer should spend the available balance");
        assertEquals(1, rejected, "the second transfer should be rejected for insufficient balance");
        assertTrue(accounts.findById("alice@mesh").orElseThrow().getBalance().signum() >= 0,
                "sender balance must never become negative");
    }

    @Test
    void insufficientBalanceIsRecordedAsRejectedWithoutChangingBalances() throws Exception {
        BigDecimal aliceBefore = accounts.findById("alice@mesh").orElseThrow().getBalance();
        MeshPacket packet = demoService.createPacket(
                "alice@mesh", "bob@mesh", new BigDecimal("999999.00"), "demo-only", 5);

        BridgeIngestionService.IngestResult result = bridge.ingest(packet, "bridge-low-balance", 1);

        assertEquals("REJECTED", result.outcome());
        assertEquals(aliceBefore, accounts.findById("alice@mesh").orElseThrow().getBalance());
    }

    @Test
    void encryptDecryptRoundTrip() throws Exception {
        PaymentInstruction original = new PaymentInstruction(
                "alice@mesh", "bob@mesh", new BigDecimal("123.45"),
                "abcdef", "nonce-1", System.currentTimeMillis());

        String ct = crypto.encrypt(original, serverKey.getPublicKey());
        PaymentInstruction decrypted = crypto.decrypt(ct);

        assertEquals(original.getSenderVpa(), decrypted.getSenderVpa());
        assertEquals(original.getReceiverVpa(), decrypted.getReceiverVpa());
        assertEquals(0, original.getAmount().compareTo(decrypted.getAmount()));
        assertEquals(original.getNonce(), decrypted.getNonce());
    }
}
