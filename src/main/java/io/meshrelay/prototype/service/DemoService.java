package io.meshrelay.prototype.service;

import io.meshrelay.prototype.crypto.HybridCryptoService;
import io.meshrelay.prototype.crypto.ServerKeyHolder;
import io.meshrelay.prototype.model.Account;
import io.meshrelay.prototype.model.AccountRepository;
import io.meshrelay.prototype.model.MeshPacket;
import io.meshrelay.prototype.model.PaymentInstruction;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

/**
 * Helper service that:
 * - seeds demo accounts on startup
 * - simulates "sender phone creates an encrypted packet" flow
 */
@Service
public class DemoService {

    private static final Logger log = LoggerFactory.getLogger(DemoService.class);

    private final AccountRepository accounts;
    private final HybridCryptoService crypto;
    private final ServerKeyHolder serverKey;

    /**
     * Constructor injection keeps the demo packet factory explicit and easy to test.
     */
    public DemoService(AccountRepository accounts, HybridCryptoService crypto, ServerKeyHolder serverKey) {
        this.accounts = accounts;
        this.crypto = crypto;
        this.serverKey = serverKey;
    }

    /**
     * Seeds predictable demo-only accounts once, leaving existing balances untouched on restart.
     */
    @PostConstruct
    public void seedAccounts() {
        // Insert each missing demo account independently. This also upgrades databases
        // that already contain some older demo accounts, without resetting balances.
        seedIfMissing("alice@mesh", "Alice Morgan", "5000.00");
        seedIfMissing("bob@mesh", "Bob Carter", "2750.00");
        seedIfMissing("carol@mesh", "Carol Diaz", "4125.50");
        seedIfMissing("dave@mesh", "David Lee", "1800.00");
        seedIfMissing("erin@mesh", "Erin Shah", "6350.75");
    }

    /**
     * Adds a fictional demo account only when its UPI ID is absent.
     */
    private void seedIfMissing(String vpa, String name, String initialBalance) {
        if (!accounts.existsById(vpa)) {
            accounts.save(new Account(vpa, name, new BigDecimal(initialBalance)));
            log.info("Seeded missing demo account {}", vpa);
        }
    }

    /**
     * Simulates a sender creating a payment packet:
     * 1. Build a PaymentInstruction with a fresh nonce and timestamp.
     * 2. Encrypt it with the server's public key using hybrid RSA/AES encryption.
     * 3. Wrap the ciphertext in a MeshPacket with a routing TTL.
     * The demo token is not an authentication credential and is never validated.
     * <p>
     * This method runs on the server only to simulate the sender. A future Android client
     * would need its own packet-creation implementation and a proper sender-authentication
     * protocol; this prototype does not establish device identity.
     */
    public MeshPacket createPacket(String senderVpa, String receiverVpa,
                                   BigDecimal amount, String demoToken, int ttl) throws Exception {
        PaymentInstruction instruction = new PaymentInstruction(
                senderVpa,
                receiverVpa,
                amount,
                sha256Hex(demoToken),
                UUID.randomUUID().toString(),       // nonce — guarantees uniqueness
                Instant.now().toEpochMilli()        // signedAt — for freshness check
        );

        String ciphertext = crypto.encrypt(instruction, serverKey.getPublicKey());

        MeshPacket packet = new MeshPacket();
        packet.setPacketId(UUID.randomUUID().toString());
        packet.setTtl(ttl);
        packet.setCreatedAt(Instant.now().toEpochMilli());
        packet.setCiphertext(ciphertext);
        return packet;
    }

    private String sha256Hex(String input) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte b : hash) hex.append(String.format("%02x", b));
        return hex.toString();
    }
}
