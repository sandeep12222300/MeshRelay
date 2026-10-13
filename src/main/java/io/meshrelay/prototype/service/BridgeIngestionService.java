package io.meshrelay.prototype.service;

import io.meshrelay.prototype.crypto.HybridCryptoService;
import io.meshrelay.prototype.model.MeshPacket;
import io.meshrelay.prototype.model.PaymentInstruction;
import io.meshrelay.prototype.model.Transaction;
import io.meshrelay.prototype.model.TransactionRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Validates and processes a packet delivered by a simulated internet-connected bridge.
 * The pipeline hashes, deduplicates, decrypts, checks freshness, and then delegates the
 * atomic ledger update. No real bank or UPI network is contacted.
 */
@Service
public class BridgeIngestionService {

    private static final Logger log = LoggerFactory.getLogger(BridgeIngestionService.class);

    private final HybridCryptoService crypto;
    private final IdempotencyService idempotency;
    private final SettlementService settlement;
    private final TransactionRepository transactions;

    @Value("${upi.mesh.packet-max-age-seconds:86400}")
    private long maxAgeSeconds;

    @Value("${upi.mesh.clock-skew-seconds:300}")
    private long allowedClockSkewSeconds;

    /**
     * Creates the ingestion pipeline with its required services.
     */
    public BridgeIngestionService(HybridCryptoService crypto, IdempotencyService idempotency,
                                  SettlementService settlement, TransactionRepository transactions) {
        this.crypto = crypto;
        this.idempotency = idempotency;
        this.settlement = settlement;
        this.transactions = transactions;
    }

    /**
     * Processes a single inbound packet and returns a stable, dashboard-friendly outcome.
     */
    public IngestResult ingest(MeshPacket packet, String bridgeNodeId, int hopCount) {
        String packetHash = null;
        boolean claimed = false;
        try {
            if (packet == null || packet.getCiphertext() == null || packet.getCiphertext().isBlank()) {
                return IngestResult.invalid("", "missing_ciphertext");
            }
            if (bridgeNodeId == null || bridgeNodeId.isBlank()) {
                return IngestResult.invalid("", "missing_bridge_id");
            }
            if (hopCount < 0 || hopCount > 100) return IngestResult.invalid("", "invalid_hop_count");

            packetHash = crypto.hashCiphertext(packet.getCiphertext());

            // Durable lookup protects against duplicates after a process restart or cache reset.
            if (transactions.existsByPacketHash(packetHash)) {
                log.info("Previously processed packet {} from bridge {} dropped",
                        shortHash(packetHash), bridgeNodeId);
                return IngestResult.duplicate(packetHash);
            }

            if (!idempotency.claim(packetHash)) {
                log.info("Duplicate packet {} from bridge {} dropped", shortHash(packetHash), bridgeNodeId);
                return IngestResult.duplicate(packetHash);
            }
            claimed = true;

            final PaymentInstruction instruction;
            try {
                instruction = crypto.decrypt(packet.getCiphertext());
            } catch (Exception ex) {
                idempotency.release(packetHash);
                log.warn("Packet {} failed authenticated decryption", shortHash(packetHash));
                return IngestResult.invalid(packetHash, "decryption_failed");
            }

            if (instruction.getSignedAt() == null || instruction.getSignedAt() <= 0) {
                idempotency.release(packetHash);
                return IngestResult.invalid(packetHash, "missing_signed_at");
            }
            long ageSeconds = (Instant.now().toEpochMilli() - instruction.getSignedAt()) / 1000;
            if (ageSeconds > maxAgeSeconds) {
                idempotency.release(packetHash);
                return IngestResult.invalid(packetHash, "stale_packet");
            }
            if (ageSeconds < -allowedClockSkewSeconds) {
                idempotency.release(packetHash);
                return IngestResult.invalid(packetHash, "future_dated");
            }

            Transaction transaction = settlement.settle(instruction, packetHash, bridgeNodeId, hopCount);
            claimed = false; // A final ledger outcome exists; keep the duplicate claim.
            if (transaction.getStatus() == Transaction.Status.REJECTED) {
                return IngestResult.rejected(packetHash, transaction);
            }
            return IngestResult.settled(packetHash, transaction);
        } catch (DataIntegrityViolationException ex) {
            if (claimed) idempotency.release(packetHash);
            // A concurrent bridge may have committed the same packet after our lookup.
            if (packetHash != null && transactions.existsByPacketHash(packetHash)) {
                return IngestResult.duplicate(packetHash);
            }
            log.error("Packet persistence constraint failed for {}", shortHash(packetHash), ex);
            return IngestResult.invalid(packetHash == null ? "" : packetHash, "persistence_conflict");
        } catch (IllegalArgumentException ex) {
            if (claimed) idempotency.release(packetHash);
            log.info("Packet rejected by business validation: {}", ex.getMessage());
            return IngestResult.invalid(packetHash == null ? "" : packetHash, "invalid_payment_instruction");
        } catch (Exception ex) {
            if (claimed) idempotency.release(packetHash);
            log.error("Unexpected ingestion failure for packet {}", shortHash(packetHash), ex);
            // Do not return internal exception messages to the caller.
            return IngestResult.invalid(packetHash == null ? "" : packetHash, "internal_error_retryable");
        }
    }

    /**
     * Keeps logs useful without exposing the complete packet fingerprint.
     */
    private String shortHash(String hash) {
        return hash == null || hash.length() < 12 ? "unknown" : hash.substring(0, 12) + "...";
    }

    /**
     * Typed response for a settled, rejected, duplicate, or invalid packet.
     */
    public record IngestResult(String outcome, String packetHash, String reason, Long transactionId) {
        public static IngestResult settled(String hash, Transaction tx) {
            return new IngestResult("SETTLED", hash, null, tx.getId());
        }

        public static IngestResult rejected(String hash, Transaction tx) {
            return new IngestResult("REJECTED", hash, "insufficient_balance", tx.getId());
        }

        public static IngestResult duplicate(String hash) {
            return new IngestResult("DUPLICATE_DROPPED", hash, null, null);
        }

        public static IngestResult invalid(String hash, String reason) {
            return new IngestResult("INVALID", hash, reason, null);
        }
    }
}
