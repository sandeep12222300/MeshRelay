package io.meshrelay.prototype.model;

import java.math.BigDecimal;

/**
 * The actual payment instruction. After the server decrypts MeshPacket.ciphertext,
 * it gets one of these.
 * <p>
 * Critical fields for security:
 * - nonce: a UUID unique to this payment. Even if everything else were identical
 * for two legitimate payments (alice sends bob ₹100 twice), the nonces
 * differ, so the resulting ciphertexts and their hashes also differ.
 * - signedAt: lets the server reject stale packets ("freshness window"). Without
 * this, an attacker who got the ciphertext could replay it weeks later.
 * - demoTokenHash: a non-sensitive demo placeholder included only to exercise payload creation.
 * It is not authentication, is not validated, and must never represent a real secret.
 */
public class PaymentInstruction {

    private String senderVpa;
    private String receiverVpa;
    private BigDecimal amount;
    private String demoTokenHash;
    private String nonce;     // UUID, unique per payment intent
    private Long signedAt;    // epoch millis, when sender signed

    public PaymentInstruction() {
    }

    public PaymentInstruction(String senderVpa, String receiverVpa, BigDecimal amount,
                              String demoTokenHash, String nonce, Long signedAt) {
        this.senderVpa = senderVpa;
        this.receiverVpa = receiverVpa;
        this.amount = amount;
        this.demoTokenHash = demoTokenHash;
        this.nonce = nonce;
        this.signedAt = signedAt;
    }

    public String getSenderVpa() {
        return senderVpa;
    }

    public void setSenderVpa(String senderVpa) {
        this.senderVpa = senderVpa;
    }

    public String getReceiverVpa() {
        return receiverVpa;
    }

    public void setReceiverVpa(String receiverVpa) {
        this.receiverVpa = receiverVpa;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public String getDemoTokenHash() {
        return demoTokenHash;
    }

    public void setDemoTokenHash(String demoTokenHash) {
        this.demoTokenHash = demoTokenHash;
    }

    public String getNonce() {
        return nonce;
    }

    public void setNonce(String nonce) {
        this.nonce = nonce;
    }

    public Long getSignedAt() {
        return signedAt;
    }

    public void setSignedAt(Long signedAt) {
        this.signedAt = signedAt;
    }
}
