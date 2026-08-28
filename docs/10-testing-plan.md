# 10 — Testing and Evaluation Plan

Two tracks: **automated** tests (repeatable, run in CI) and **field** tests
(real phones, real radio). The proposal's TESTS 1–8 are covered by both where
possible — automation proves the protocol, phones prove the physics.

## 10.1 Proposal test scenarios

| Test | Scenario | Expected | Automated | Field? |
|---|---|---|---|:--:|
| 1 | Offline local creation | Emergency stored locally, user sees confirmation | instrumented Android test (pending) | ✓ |
| 2 | One-hop relay A→B | B stores the packet | ✅ `one hop relay delivers the packet to a neighbour` | ✓ |
| 3 | Multi-hop relay A→B→C→D | D receives it, hop=3, TTL=7 | ✅ `packet traverses a four node chain and arrives intact` | ✓ |
| 4 | Duplicate detection | Exactly one logical emergency | ✅ two tests, one per defence layer, + server tests | ✓ |
| 5 | TTL expiration | Forwarding stops at TTL 0; packet still syncable | ✅ `forwarding halts when ttl is exhausted` | ✓ |
| 6 | Deferred sync | Offline chain → one device gets signal → reaches MySQL | ✅ server test | ✓ |
| 7 | Direct online sync | Immediate delivery | ✅ server test | ✓ |
| 8 | Responder workflow | Assignment received, status transitions recorded | ✅ `ResponderWorkflowTest` | ✓ |

**Status: TESTS 2–8 are automated and passing.** 37 Kotlin tests in
`:core-mesh` and 66 PHP tests in the backend, run with
`gradle :core-mesh:test` and `php artisan test`.

BLE has no emulator, so the radio itself still needs phones. But the relay
*protocol* — dedup, TTL, hop accounting, forwarding eligibility — is covered by
`VirtualMesh`, an in-memory `MeshTransport` that wires N nodes together with a
configurable topology, loss rate and latency. Field testing therefore measures
range, interference and battery; it is no longer where correctness is first
discovered.

### Duplicate detection has two layers, and both are tested

This distinction is worth making explicitly at defense:

1. **Anti-entropy digest (first line).** Peers exchange a Bloom filter of held
   packet ids before any payload moves, so a redundant copy is normally *never
   transmitted at all*. Verified by `a packet reaching a node by two routes is
   never sent twice`.
2. **Seen-set (second line).** A duplicate can still arrive — Bloom filters have
   false negatives by construction only in the safe direction, but two peers can
   be mid-transfer simultaneously. Verified by `a duplicate that reaches a node
   anyway is recognised and dropped`.

A test asserting only the second layer initially *failed*, because the first
layer had already prevented the duplicate. That is the system behaving better
than the test expected, and both properties are now asserted separately.

## 10.2 Server test suite (Pest/PHPUnit)

**Ingestion**
- Same `packet_id` twice → one packet row, second `DUPLICATE`, one incident.
- Same `emergency_id` via two different packet ids → **one** incident, two
  packet rows retained as routing evidence.
- `ttl_remaining = 0` → accepted and stored, marked terminal, still syncs.
- Tampered payload → `INVALID_HMAC`, incident **not** created.
- Packet referencing an emergency not yet present → accepted, reconciled on arrival.
- Late packet for a `RESOLVED` incident → routing recorded, status untouched.
- Entire batch replayed → database state byte-identical.

**Priority**
- Worked examples A–D from `08-priority-engine.md` produce exactly 81/35/15/100.
- Escalation rules fire and are recorded in the breakdown.
- Ageing job raises a stale `NEW` incident by one band and writes `status_history`.
- A manual override is not undone by the ageing job.

**RBAC**
- Responder cannot read another team's incident (403).
- Operator cannot lower priority (403); official can.
- Device token cannot list incidents (403).
- Official cannot modify a sysadmin (403).

**Clock skew**
- Device clock 40 min fast → corrected delay positive; raw delay would be negative.
- Uncorrectable case → `clock_anomaly`, excluded from delay statistics.

## 10.2a Device data-layer suite (JVM, `:data`) — 47 tests, all passing

**`ReportRepositoryTest` (14)** — the local-first write path: saving succeeds
with no network client in existence, identifiers are device-minted, packets are
signed and verifiable, an unregistered device can still report, a report needs
nothing but a type, peer reports dedup on `packet_id`, and re-meeting a peer does
not double-count a handoff.

**`SyncCoordinatorTest` (18)** — priority-before-age ordering, unscored
life-threatening reports outranking scored LOW ones, batch caps, accepted /
duplicate / TTL-expired all counting as delivered, permanent rejections retiring
rather than retrying forever, transport failures leaving everything queued,
unmentioned packets never assumed delivered, nothing sent while offline, and
exponential backoff whose jitter spreads retries across devices.

**`DeliveryStateMachineTest` (9)** — evidence-only advancement, and the case that
matters most: stale mesh evidence cannot reopen a resolved report.

**`WireContractTest` (6)** — snake_case field names, no camelCase leaks,
ISO-8601 UTC timestamps, lenient response parsing, and unknown statuses treated
as retryable rather than successful.

### The wire contract is checked against the real validator

`android/data/contract-check.sh` takes the sync request Kotlin actually produces
and POSTs it to a running Laravel server. The `SyncPacketsRequest` validator gets
the final say.

This exists because reading two codebases and assuming they agree is precisely
how the HMAC canonicalisation defect survived undetected — the backend's own
tests passed, because they shared the bug.

## 10.3 Mesh test suite (JVM, `:core-mesh`) — 37 tests, all passing

**`RelayScenarioTest` (13)** — one-hop delivery; four-node chain arriving at
hop 3 / TTL 7 with packet id, payload and signature unchanged; duplicate
suppression at both layers; TTL exhaustion halting forwarding while the packet
stays syncable; ring convergence; concurrent emergencies from two origins;
a 20-node mesh at 30% loss never forking a report; no relay back to the origin;
battery floor; relay age limit; sync bookkeeping.

**`CanonicalPacketTest` (8)** — the cross-language signing vector (below),
relay-invariant signatures, tamper detection, field-boundary forgery,
unregistered devices.

**`ChunkFramingTest` (10)** — MTU-sized fragmentation, byte-identical
reassembly, out-of-order arrival, missing fragments never assembling, abandoned
transfers expiring rather than leaking, interleaved transfers from two peers,
version and truncation rejection, CRC corruption detection.

**`BloomDigestTest` (6)** — the never-absent guarantee, false-positive rate
below 5% at the designed 200-packet load, wire round-trip, defensive copying.

### The cross-language signing vector

The single check that proves a real phone's packets will verify on the server:
one fixture is signed independently in Kotlin and in PHP, and both must produce

```
f8c462f8b8f3d32fa09a8431202b448b
```

The fixture deliberately contains a newline, a forward slash, non-ASCII
characters, a null field, a negative coordinate, and a value needing rounding —
every case where two languages' JSON encoders would have diverged.

Without this vector the failure mode is silent: the backend's own tests would
still pass, because they would share the bug. It would surface only as "the mesh
doesn't work" during device testing.

## 10.4 Field test protocol

**Setup:** 3–5 Android devices (mixed manufacturers and OS versions — this is
where compatibility problems actually appear), airplane mode with Bluetooth on,
one device with mobile data, a measuring tape, and a pre-registered barangay
location with permission obtained.

**Runs:** each configuration 10 times. Record every run, including failures.
A capstone that reports 100% success has not tested honestly.

| Variable | Levels |
|---|---|
| Distance | 5 m · 10 m · 20 m · 30 m |
| Obstruction | line of sight · one concrete wall · outdoor with people |
| Chain length | 1 · 2 · 3 hops |
| Battery | >50% · <20% |
| Screen | on · off (this one will hurt; report it) |

**Recorded per run:** discovery time, transfer time, corrected transmission
delay, hop count, success/failure, failure cause.

## 10.5 §24 operational metrics — how each is obtained

| # | Metric | Source |
|---|---|---|
| 1 | Emergency creation time | Android instrumented test + stopwatch during SUS sessions |
| 2 | Device discovery time | `packet_logs` `RELAY_SENT` minus scan start, logged by the BLE service |
| 3 | One-hop delivery success | field runs, `hop_count = 1` |
| 4 | Multi-hop delivery success | field runs + `VirtualMesh`, `hop_count ≥ 2` |
| 5 | Transmission delay | `received_at_server − (created_at_device − clock_offset_ms)` |
| 6 | Duplicate suppression | count of `DUPLICATE_SUPPRESSED` in `packet_logs` |
| 7 | TTL enforcement | count of `TTL_EXPIRED`; assert zero packets with `hop > ttl_initial` |
| 8 | Synchronisation success | `sync_logs.outcome` distribution |
| 9 | Dashboard update time | server timestamp → Livewire render, browser instrumentation |
| 10 | Battery impact | Android Battery Historian over a 4-hour relay session |

All of these are exposed by `GET /api/v1/metrics/evaluation` and rendered on the
Packet Monitoring page. **The system produces its own evaluation dataset** — no
manual tallying the week before defense.

## 10.6 ISO/IEC 25010 mapping

| Characteristic | Evidence |
|---|---|
| Functional suitability | TESTS 1–8; feature test suite green |
| Performance efficiency | metrics 1, 2, 5, 9, 10 |
| Compatibility | field matrix across manufacturers and OS versions |
| Usability | SUS + task-completion observation |
| Reliability | metrics 3, 4, 6, 7, 8; loss-rate simulations |
| Security | RBAC tests, HMAC verification tests, audit-log completeness |
| Maintainability | test coverage, `:core-mesh` isolation, documented config |
| Portability | min-SDK matrix; server runs on stock LAMP |

## 10.7 System Usability Scale

Standard 10-item SUS, 5-point Likert, administered separately to residents,
responders, and operators — the three groups have genuinely different tasks and
averaging them would hide problems. Report N, mean, SD, and per-group scores.
Participants, consent, and ethics clearance are **TO BE VALIDATED** with the
barangay and the college research committee before any human-subject session.

## 10.8 Test data policy

Development and demonstration use **synthetic** data only, seeded by
`DatabaseSeeder` and clearly marked. No real resident's emergency, name, contact,
or location enters the repository or any screenshot. Field-test reports use
consented participant data, retained only as long as the study requires (§22).
