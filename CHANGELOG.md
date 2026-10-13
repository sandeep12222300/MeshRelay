# Changelog

## 1.0.0 — MeshRelay prototype enhancement

- Renamed the application and Java package to MeshRelay (`io.meshrelay.prototype`).
- Added constructor injection to core controllers/services and injected Spring's configured `ObjectMapper`.
- Added request DTO validation and a shared JSON exception response handler.
- Added automatic Hibernate schema management and a PostgreSQL runtime profile with Docker Compose.
- Added deterministic account locking and atomic simulated ledger updates.
- Improved idempotency TTL handling and release of claims after transient/invalid processing failures.
- Added explicit `REJECTED` outcome for insufficient funds.
- Added tests for competing transfers and insufficient-balance behavior.
- Updated dashboard labels to describe a simulator and removed PIN-like wording in favor of a non-sensitive demo token.
- Added architecture, threat-boundary, setup, API, and limitation documentation.


## Regression repair — 2026-10-13

- Fixed the default packet injection device from the removed `phone-alice` ID to `phone-1`.
- Changed demo account seeding to add missing accounts individually without resetting existing balances.
- Added compatibility handling for legacy demo VPAs while preserving balances and ensuring the optimistic-lock version column exists.
- Updated the concurrency tests to use the current five demo VPAs and restore all five deterministic balances.
- Added regression tests for five seeded accounts, five offline-by-default devices, connectivity selection, and duplicate detection after clearing the in-memory cache.
- Enabled Spring scheduling so idempotency-cache expiry runs.
- Added durable transaction-hash lookup before accepting a packet and duplicate handling for concurrent database uniqueness conflicts.
- Updated README examples and descriptions to match current UPI IDs and device names.
- Switched schema management to Hibernate automatic updates; migration scripts are no longer used.
