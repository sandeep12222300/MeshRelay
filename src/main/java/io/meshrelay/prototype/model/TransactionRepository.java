package io.meshrelay.prototype.model;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Persistence queries for the simulated transaction ledger.
 */
public interface TransactionRepository extends JpaRepository<Transaction, Long> {

    /**
     * Returns the latest twenty ledger outcomes for the dashboard.
     */
    List<Transaction> findTop20ByOrderByIdDesc();

    /**
     * Checks whether a packet fingerprint already has a durable ledger outcome.
     */
    boolean existsByPacketHash(String packetHash);
}
