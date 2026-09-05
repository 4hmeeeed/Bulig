# Bulig

**An Offline-First Emergency Communication and Disaster Response Coordination
System for a Selected Barangay in Tacloban City**

*Bulig* is Waray-Waray for "help."

A resident creates an emergency report with no internet. The report is stored on
their phone, relayed to nearby phones over Bluetooth Low Energy, forwarded again
until it reaches a device with connectivity, and only then synchronised to a
Laravel/MySQL server and a barangay command center.

**Creation, storage, and relay require no internet. Internet is needed only at
the final hop.**

> Bulig is a 4th-year IT capstone prototype for a single pilot barangay. Delivery
> is opportunistic and not guaranteed, and the system does not replace official
> emergency services. See [`docs/LIMITATIONS.md`](docs/LIMITATIONS.md).

---

## Running it

**[`RUNNING.md`](RUNNING.md)** — the ordered runbook: prove the tests, start the
backend, build the app, then two phones. Start there.

```bash
cd android && ./gradlew :core-mesh:test :data:test   # 432 tests, no Android SDK needed
cd backend && php artisan test                       # 66 tests
```

---

## Repository layout

```
docs/        System design — architecture, ERD, API contract, BLE protocol, testing plan
backend/     Laravel 12 REST API + Livewire barangay command center
android/     core-mesh (relay engine) · data (storage + sync) · app (Compose UI)
```

The two Kotlin modules that carry the logic are pure JVM and fully tested. The
Compose module is authored but **not yet compiled** — see
[`android/BUILDING.md`](android/BUILDING.md).

## Design documents

| Document | Covers |
|---|---|
| [`01-architecture.md`](docs/01-architecture.md) | System overview and principles |
| [`02-roles-permissions.md`](docs/02-roles-permissions.md) | Five roles, permission matrix, enforcement |
| [`03-screens-navigation.md`](docs/03-screens-navigation.md) | Every screen, resident / responder / command center |
| [`04-database-erd.md`](docs/04-database-erd.md) | ERD and data dictionary |
| [`05-api-contract.md`](docs/05-api-contract.md) | REST endpoints, payloads, error codes |
| [`06-ble-protocol.md`](docs/06-ble-protocol.md) | GATT profile, chunked framing, anti-entropy, TTL |
| [`07-offline-sync.md`](docs/07-offline-sync.md) | Local-first writes, idempotency, clock skew |
| [`08-priority-engine.md`](docs/08-priority-engine.md) | Explainable rule-based prioritisation |
| [`09-workflows.md`](docs/09-workflows.md) | Emergency, responder, and command-center flows |
| [`10-testing-plan.md`](docs/10-testing-plan.md) | TESTS 1–8, ISO/IEC 25010, SUS, metrics |
| [`LIMITATIONS.md`](docs/LIMITATIONS.md) | Honest scope and known constraints |
| [`design/HANDOFF.md`](docs/design/HANDOFF.md) | Mobile UI design bundle (12 artboards) |
| [`design/DESIGN-RECONCILIATION.md`](docs/design/DESIGN-RECONCILIATION.md) | Where the design and the built system agree, differ, and why |
| [`design/DIAGRAMS-BRIEF.md`](docs/design/DIAGRAMS-BRIEF.md) | Brief for the use case, ERD, and system flowchart diagrams |

## Running the backend

Requires PHP 8.2+, Composer, Node 18+, and MySQL 8 (SQLite works for local runs).

```bash
cd backend
composer install
npm install
cp .env.example .env
php artisan key:generate
php artisan migrate --seed
npm run build
php artisan serve
```

Sign in at `/login` with a seeded account (all use the password `password`):

| Account | Role |
|---|---|
| `operator@bulig.test` | Barangay operator |
| `official@bulig.test` | Barangay official |
| `sysadmin@bulig.test` | System administrator |

All seeded data is **synthetic**. No real resident's emergency, name, contact, or
location appears anywhere in this repository.

## Tests

```bash
cd backend && php artisan test
```

```bash
cd android && gradle test
```

They cover packet deduplication,
idempotent re-synchronisation, TTL enforcement, HMAC verification, clock-skew
correction, the priority worked examples from `docs/08-priority-engine.md`,
role-based access control, the responder workflow, multi-hop mesh delivery, the
local-first write path, delivery-state honesty, and sync batching and backoff.

**182 tests in total** — 66 PHP, 116 Kotlin.

Three contracts are pinned across languages rather than assumed:

| Contract | How it is checked |
|---|---|
| Packet **signing** | one fixture, the same expected MAC asserted in both suites |
| Sync **wire format** | `android/data/contract-check.sh` posts Kotlin-generated JSON to a live Laravel validator |
| **Priority** scoring | `android/core-mesh/priority-parity-check.sh` runs the same worked examples through both engines |

## Field data

Barangay-specific facts — current emergency procedure, population, response
times, communication tools, connectivity conditions — are marked
**TO BE VALIDATED** and must come from interviews, observation, and approved
barangay records. No placeholder statistics are used.

## Android — `:core-mesh`

The store-and-forward relay engine: dedup, TTL, hop count, forwarding policy,
Bloom-filter anti-entropy, BLE chunk framing, and packet signing. Pure
Kotlin/JVM with **zero Android imports**, so it builds and tests anywhere —
including CI with no Android SDK.

```bash
cd android && gradle :core-mesh:test
```

Bluetooth cannot be emulated, but relay *logic* does not need a radio.
`MeshTransport` is an interface that `VirtualMesh` implements in memory and the
BLE service will implement on-device — which is what turns proposal TESTS 2–5
into ordinary unit tests.
