# 10 — Testing and Evaluation Plan

Two tracks: **automated** tests (repeatable, run in CI) and **field** tests
(real phones, real radio). The proposal's TESTS 1–8 are covered by both where
possible — automation proves the protocol, phones prove the physics.

## 10.1 Proposal test scenarios

| Test | Scenario | Expected | Automated? | Field? |
|---|---|---|:--:|:--:|
| 1 | Offline local creation | Emergency stored locally, user sees confirmation | ✓ instrumented Android test | ✓ |
| 2 | One-hop relay A→B | B stores the packet | ✗ radio | ✓ |
| 3 | Two-hop relay A→B→C | C receives it, hop=2, TTL=8 | ✓ `VirtualMesh` | ✓ |
| 4 | Duplicate detection | Exactly one logical emergency | ✓ mesh + server tests | ✓ |
| 5 | TTL expiration | Forwarding stops at TTL 0; packet still syncable | ✓ `VirtualMesh` | ✓ |
| 6 | Deferred sync | Offline chain → one device gets signal → reaches MySQL | ✓ server test | ✓ |
| 7 | Direct online sync | Immediate delivery | ✓ server test | ✓ |
| 8 | Responder workflow | Assignment received, status transitions recorded | ✓ feature test | ✓ |

Tests 2 and 3 cannot be fully automated — BLE has no emulator support. The
`VirtualMesh` fake covers the *logic*; the phones cover the *radio*. Both results
are reported.

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

## 10.3 Mesh test suite (JVM, `:core-mesh`)

`VirtualMesh` builds N nodes with a configurable topology, loss rate, and latency:

- Linear A→B→C→D: packet reaches D at hop 3, TTL 7.
- Ring topology: no infinite circulation; every node's seen-set stabilises.
- TTL 2 in a 5-node chain: packet halts at node 3, and is still syncable there.
- 20 nodes, 30% loss: delivery ratio recorded; no duplicate emergencies anywhere.
- Bloom-filter false positive: causes a skip, never a duplicate.
- Chunk reassembly: fragments out of order, fragments lost, peer disconnect
  mid-transfer → buffer expires cleanly, no leak.

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
