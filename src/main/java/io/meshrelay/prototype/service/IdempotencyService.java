package io.meshrelay.prototype.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * JVM-local duplicate gate for the demo packet ingestion path.
 * <p>
 * Atomic map operations ensure that concurrent bridge threads in this process do not
 * process the same ciphertext at the same time. A database uniqueness constraint on the
 * ledger is also used as defense in depth. This cache is intentionally not presented as
 * a distributed, multi-instance idempotency solution.
 */
@Service
public class IdempotencyService {

    private final Map<String, Instant> seen = new ConcurrentHashMap<>();

    @Value("${upi.mesh.idempotency-ttl-seconds:86400}")
    private long ttlSeconds;

    /**
     * Atomically claims a hash, allowing a fresh claim after its configured TTL.
     */
    public boolean claim(String packetHash) {
        Instant now = Instant.now();
        AtomicBoolean claimed = new AtomicBoolean(false);
        seen.compute(packetHash, (hash, previousClaim) -> {
            if (previousClaim == null || previousClaim.plusSeconds(ttlSeconds).isBefore(now)) {
                claimed.set(true);
                return now;
            }
            return previousClaim;
        });
        return claimed.get();
    }

    /**
     * Releases a claim when processing fails before a final ledger outcome is committed.
     */
    public void release(String packetHash) {
        if (packetHash != null) seen.remove(packetHash);
    }

    /**
     * Returns the number of cached hashes, primarily for the demo dashboard and tests.
     */
    public int size() {
        return seen.size();
    }

    /**
     * Periodically removes entries whose idempotency window has expired.
     */
    @Scheduled(fixedDelay = 60_000)
    public void evictExpired() {
        Instant cutoff = Instant.now().minusSeconds(ttlSeconds);
        seen.entrySet().removeIf(entry -> entry.getValue().isBefore(cutoff));
    }

    /**
     * Clears the in-memory cache for deterministic demo/test resets.
     */
    public void clear() {
        seen.clear();
    }
}
