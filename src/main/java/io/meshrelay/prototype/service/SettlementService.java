package io.meshrelay.prototype.service;

import io.meshrelay.prototype.model.Account;
import io.meshrelay.prototype.model.AccountRepository;
import io.meshrelay.prototype.model.PaymentInstruction;
import io.meshrelay.prototype.model.Transaction;
import io.meshrelay.prototype.model.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Applies a simulated transfer to the local demo ledger.
 * <p>
 * Both account updates and the transaction record are committed in one database transaction.
 * Pessimistic row locks serialize competing debits, while the unique packet hash on the
 * transaction table is a second line of defense against duplicate settlement.
 * This service does not connect to or represent a real bank ledger.
 */
@Service
public class SettlementService {

    private static final Logger log = LoggerFactory.getLogger(SettlementService.class);

    private final AccountRepository accounts;
    private final TransactionRepository transactions;

    /**
     * Creates the settlement service with its persistence dependencies.
     */
    public SettlementService(AccountRepository accounts, TransactionRepository transactions) {
        this.accounts = accounts;
        this.transactions = transactions;
    }

    /**
     * Validates and applies one payment instruction atomically.
     * Insufficient funds are recorded as REJECTED; invalid account/amount data is rejected
     * before any balance changes are made.
     */
    @Transactional
    public Transaction settle(PaymentInstruction instruction, String packetHash,
                              String bridgeNodeId, int hopCount) {
        validateInstruction(instruction, packetHash, bridgeNodeId, hopCount);

        List<Account> lockedAccounts = accounts.findAllForSettlement(
                List.of(instruction.getSenderVpa(), instruction.getReceiverVpa()));
        Map<String, Account> byVpa = lockedAccounts.stream()
                .collect(Collectors.toMap(Account::getVpa, Function.identity()));

        Account sender = byVpa.get(instruction.getSenderVpa());
        Account receiver = byVpa.get(instruction.getReceiverVpa());
        if (sender == null) {
            throw new IllegalArgumentException("Unknown sender VPA: " + instruction.getSenderVpa());
        }
        if (receiver == null) {
            throw new IllegalArgumentException("Unknown receiver VPA: " + instruction.getReceiverVpa());
        }

        BigDecimal amount = instruction.getAmount();
        if (sender.getBalance().compareTo(amount) < 0) {
            log.warn("Insufficient demo balance for {}: requested amount {}", sender.getVpa(), amount);
            return recordTransaction(instruction, packetHash, bridgeNodeId, hopCount,
                    Transaction.Status.REJECTED);
        }

        sender.setBalance(sender.getBalance().subtract(amount));
        receiver.setBalance(receiver.getBalance().add(amount));
        accounts.save(sender);
        accounts.save(receiver);

        Transaction transaction = recordTransaction(instruction, packetHash, bridgeNodeId,
                hopCount, Transaction.Status.SETTLED);
        log.info("Demo transfer {}: {} -> {} amount={}", transaction.getId(),
                sender.getVpa(), receiver.getVpa(), amount);
        return transaction;
    }

    /**
     * Rejects malformed or unsafe transfer instructions before touching the ledger.
     */
    private void validateInstruction(PaymentInstruction instruction, String packetHash,
                                     String bridgeNodeId, int hopCount) {
        if (instruction == null) throw new IllegalArgumentException("Payment instruction is required");
        if (instruction.getSenderVpa() == null || instruction.getSenderVpa().isBlank())
            throw new IllegalArgumentException("Sender VPA is required");
        if (instruction.getReceiverVpa() == null || instruction.getReceiverVpa().isBlank())
            throw new IllegalArgumentException("Receiver VPA is required");
        if (instruction.getSenderVpa().equals(instruction.getReceiverVpa()))
            throw new IllegalArgumentException("Sender and receiver must be different accounts");
        if (instruction.getAmount() == null || instruction.getAmount().signum() <= 0)
            throw new IllegalArgumentException("Amount must be positive");
        if (instruction.getAmount().scale() > 2)
            throw new IllegalArgumentException("Amount must have at most two decimal places");
        if (instruction.getSignedAt() == null || instruction.getSignedAt() <= 0)
            throw new IllegalArgumentException("A valid signedAt timestamp is required");
        if (packetHash == null || packetHash.length() != 64)
            throw new IllegalArgumentException("A valid SHA-256 packet hash is required");
        if (bridgeNodeId == null || bridgeNodeId.isBlank())
            throw new IllegalArgumentException("Bridge node ID is required");
        if (hopCount < 0) throw new IllegalArgumentException("Hop count cannot be negative");
    }

    /**
     * Persists the final outcome alongside the packet's settlement metadata.
     */
    private Transaction recordTransaction(PaymentInstruction instruction, String packetHash,
                                          String bridgeNodeId, int hopCount,
                                          Transaction.Status status) {
        Transaction transaction = new Transaction();
        transaction.setPacketHash(packetHash);
        transaction.setSenderVpa(instruction.getSenderVpa());
        transaction.setReceiverVpa(instruction.getReceiverVpa());
        transaction.setAmount(instruction.getAmount().setScale(2));
        transaction.setSignedAt(Instant.ofEpochMilli(instruction.getSignedAt()));
        transaction.setSettledAt(Instant.now());
        transaction.setBridgeNodeId(bridgeNodeId);
        transaction.setHopCount(hopCount);
        transaction.setStatus(status);
        return transactions.saveAndFlush(transaction);
    }
}
