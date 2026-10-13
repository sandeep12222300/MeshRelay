package io.meshrelay.prototype.crypto;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Base64;

/**
 * Holds the server's RSA keypair.
 * <p>
 * In production, the private key would live in an HSM (Hardware Security Module)
 * or at least a KMS like AWS KMS / HashiCorp Vault. NEVER in the JAR or source.
 * <p>
 * For this demo we generate a fresh keypair on every startup. The public key is
 * exposed via /api/server-key so the (simulated) sender devices can use it to
 * encrypt payloads.
 */
@Component
public class ServerKeyHolder {

    private static final Logger log = LoggerFactory.getLogger(ServerKeyHolder.class);

    private KeyPair keyPair;

    /**
     * Generates a fresh ephemeral RSA keypair for this local demo process.
     */
    @PostConstruct
    public void init() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        this.keyPair = gen.generateKeyPair();
        log.info("Server RSA keypair generated (2048-bit). Public key fingerprint: {}",
                getPublicKeyBase64().substring(0, 32) + "...");
    }

    /**
     * Returns the public key used to encrypt simulated payment packets.
     */
    public PublicKey getPublicKey() {
        return keyPair.getPublic();
    }

    /**
     * Returns the private key used by the backend to decrypt packets.
     */
    public PrivateKey getPrivateKey() {
        return keyPair.getPrivate();
    }

    /**
     * Exposes the encoded public key for local demo clients.
     */
    public String getPublicKeyBase64() {
        return Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
    }
}
