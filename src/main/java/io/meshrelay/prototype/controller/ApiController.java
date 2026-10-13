package io.meshrelay.prototype.controller;

import io.meshrelay.prototype.crypto.ServerKeyHolder;
import io.meshrelay.prototype.model.*;
import io.meshrelay.prototype.service.*;
import org.springframework.http.ResponseEntity;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.*;

/**
 * Public REST surface.
 * <p>
 * The endpoints split into three groups:
 * /api/server-key      → so simulated senders can fetch the server's public key
 * /api/mesh/*          → simulator endpoints (inject, gossip, flush)
 * /api/bridge/ingest   → simulated bridge-ingestion endpoint (not production-secured)
 * /api/accounts, /api/transactions → for the dashboard
 */
@RestController
@RequestMapping("/api")
public class ApiController {

    private final ServerKeyHolder serverKey;
    private final DemoService demo;
    private final MeshSimulatorService mesh;
    private final BridgeIngestionService bridge;
    private final AccountRepository accountRepo;
    private final TransactionRepository txRepo;
    private final IdempotencyService idempotency;

    /**
     * Constructor injection makes the controller's dependencies explicit and testable.
     */
    public ApiController(ServerKeyHolder serverKey, DemoService demo, MeshSimulatorService mesh,
                         BridgeIngestionService bridge, AccountRepository accountRepo,
                         TransactionRepository txRepo, IdempotencyService idempotency) {
        this.serverKey = serverKey;
        this.demo = demo;
        this.mesh = mesh;
        this.bridge = bridge;
        this.accountRepo = accountRepo;
        this.txRepo = txRepo;
        this.idempotency = idempotency;
    }

    // ------------------------------------------------------------------ key

    /**
     * Publishes the demo public key used to encrypt simulated sender payloads.
     */
    @GetMapping("/server-key")
    public Map<String, String> getServerPublicKey() {
        return Map.of(
                "publicKey", serverKey.getPublicKeyBase64(),
                "algorithm", "RSA-2048 / OAEP-SHA256",
                "hybridScheme", "RSA-OAEP encrypts an AES-256-GCM session key"
        );
    }

    // ---------------------------------------------------------------- demo

    /**
     * Demo helper: build a packet on the server (simulating a sender phone)
     * and inject it into the mesh at the given device.
     */
    @PostMapping("/demo/send")
    public ResponseEntity<?> demoSend(@Valid @RequestBody DemoSendRequest req) throws Exception {
        MeshPacket packet = demo.createPacket(
                req.senderVpa, req.receiverVpa, req.amount, req.demoToken,
                req.ttl == null ? 5 : req.ttl);

        String startDevice = req.startDevice == null || req.startDevice.isBlank() ? "phone-1" : req.startDevice;
        mesh.inject(startDevice, packet);

        return ResponseEntity.ok(Map.of(
                "packetId", packet.getPacketId(),
                "ciphertextPreview", packet.getCiphertext().substring(0, 64) + "...",
                "ttl", packet.getTtl(),
                "injectedAt", startDevice
        ));
    }

    /**
     * Validated request contract for a local-only simulated payment.
     */
    public static class DemoSendRequest {
        @NotBlank
        @Size(max = 255)
        public String senderVpa;
        @NotBlank
        @Size(max = 255)
        public String receiverVpa;
        @NotNull
        @DecimalMin(value = "0.01")
        @Digits(integer = 17, fraction = 2)
        public BigDecimal amount;
        @NotBlank
        @Size(min = 4, max = 64)
        public String demoToken; // Demo placeholder only; never enter a real banking credential.
        @Min(0)
        @jakarta.validation.constraints.Max(20)
        public Integer ttl;
        @Size(max = 100)
        public String startDevice;
    }

    // -------------------------------------------------------------- mesh sim

    /**
     * Returns the current in-memory mesh topology and each virtual device's packet count.
     */
    @GetMapping("/mesh/state")
    public Map<String, Object> meshState() {
        List<Map<String, Object>> deviceData = new ArrayList<>();
        for (VirtualDevice d : mesh.getDevices()) {
            deviceData.add(Map.of(
                    "deviceId", d.getDeviceId(),
                    "hasInternet", d.hasInternet(),
                    "packetCount", d.packetCount(),
                    "packetIds", d.getHeldPackets().stream()
                            .map(p -> p.getPacketId().substring(0, 8))
                            .toList()
            ));
        }
        return Map.of(
                "devices", deviceData,
                "idempotencyCacheSize", idempotency.size()
        );
    }

    /**
     * Sets one virtual device's simulated connectivity state.
     */
    @PostMapping("/mesh/devices/{deviceId}/connectivity")
    public ResponseEntity<?> setDeviceConnectivity(@PathVariable String deviceId,
                                                   @RequestBody ConnectivityRequest request) {
        if (request == null || request.online == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "online must be provided"));
        }
        if (!mesh.setConnectivity(deviceId, request.online)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of("deviceId", deviceId, "online", request.online));
    }

    /**
     * Sets the requested number of virtual devices online; all remaining devices go offline.
     */
    @PostMapping("/mesh/connectivity")
    public ResponseEntity<?> setOnlineDeviceCount(@RequestBody OnlineCountRequest request) {
        if (request == null || request.onlineCount == null || request.onlineCount < 0 || request.onlineCount > 5) {
            return ResponseEntity.badRequest().body(Map.of("error", "onlineCount must be between 0 and 5"));
        }
        mesh.setOnlineDeviceCount(request.onlineCount);
        return ResponseEntity.ok(Map.of("onlineCount", request.onlineCount,
                "offlineCount", 5 - request.onlineCount));
    }

    public static class ConnectivityRequest {
        public Boolean online;
    }

    public static class OnlineCountRequest {
        public Integer onlineCount;
    }

    /**
     * Advances the simulator by one packet-relay round.
     */
    @PostMapping("/mesh/gossip")
    public Map<String, Object> meshGossip() {
        MeshSimulatorService.GossipResult r = mesh.gossipOnce();
        return Map.of(
                "transfers", r.transfers(),
                "deviceCounts", r.deviceCounts()
        );
    }

    /**
     * "All bridge nodes simultaneously walk outside and get 4G."
     * They all upload everything they hold to /api/bridge/ingest.
     * <p>
     * THIS is the moment the duplicate-storm idempotency case is tested:
     * if multiple bridge nodes hold the same packet, the server gets multiple
     * concurrent POSTs of the same ciphertext, and only one should settle.
     */
    @PostMapping("/mesh/flush")
    public Map<String, Object> meshFlush() {
        List<MeshSimulatorService.BridgeUpload> uploads = mesh.collectBridgeUploads();

        List<Map<String, Object>> results = new ArrayList<>();
        // Upload them in parallel to actually exercise concurrent idempotency.
        uploads.parallelStream().forEach(up -> {
            BridgeIngestionService.IngestResult r =
                    bridge.ingest(up.packet(), up.bridgeNodeId(), Math.max(0, 5 - up.packet().getTtl()));
            synchronized (results) {
                results.add(Map.of(
                        "bridgeNode", up.bridgeNodeId(),
                        "packetId", up.packet().getPacketId().substring(0, 8),
                        "outcome", r.outcome(),
                        "reason", r.reason() == null ? "" : r.reason(),
                        "transactionId", r.transactionId() == null ? -1 : r.transactionId()
                ));
            }
        });

        return Map.of(
                "uploadsAttempted", uploads.size(),
                "results", results
        );
    }

    /**
     * Clears virtual-device packet queues and the process-local duplicate cache.
     */
    @PostMapping("/mesh/reset")
    public Map<String, Object> meshReset() {
        mesh.resetMesh();
        idempotency.clear();
        return Map.of("status", "mesh and idempotency cache cleared");
    }

    // -------------------------------------------------------------- bridge

    /**
     * Accepts a packet from a simulated bridge node. The endpoint is intentionally
     * unauthenticated for local demonstration only and must not be exposed publicly.
     */
    @PostMapping("/bridge/ingest")
    public ResponseEntity<?> ingest(
            @Valid @RequestBody MeshPacket packet,
            @RequestHeader(value = "X-Bridge-Node-Id", defaultValue = "unknown") String bridgeNodeId,
            @RequestHeader(value = "X-Hop-Count", defaultValue = "0") int hopCount) {

        BridgeIngestionService.IngestResult r = bridge.ingest(packet, bridgeNodeId, hopCount);
        return ResponseEntity.ok(r);
    }

    // ------------------------------------------------------------- accounts

    /**
     * Returns the fictional demo accounts and their current simulated balances.
     */
    @GetMapping("/accounts")
    public List<Account> listAccounts() {
        return accountRepo.findAll();
    }

    /**
     * Returns the most recent simulated ledger outcomes for the dashboard.
     */
    @GetMapping("/transactions")
    public List<Transaction> listTransactions() {
        return txRepo.findTop20ByOrderByIdDesc();
    }
}
