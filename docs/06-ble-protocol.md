# 06 — BLE Mesh Protocol (Bulig Relay Protocol v1)

The heart of the project. This document specifies how an emergency packet moves
between phones with no infrastructure.

## 6.1 Roles: every device is both server and client

A device that only scans can never *receive*. A device that only advertises can
never *forward*. So every Bulig install runs both GATT roles concurrently inside
a single foreground service:

- **Peripheral role** — advertises the Bulig service UUID; hosts the GATT
  characteristics below; accepts writes from peers.
- **Central role** — scans for the Bulig service UUID; connects to discovered
  peers; writes packets to them.

The service alternates duty cycles (advertise window / scan window) rather than
running both continuously, to bound battery cost. Defaults in `settings`:
scan 10 s every 60 s, advertise continuously at `ADVERTISE_MODE_BALANCED`.

## 6.2 GATT profile

**Service UUID** `b0116000-8bad-4f9a-9c1e-0000bul16000` (placeholder; a final
random 128-bit UUID is fixed before pilot deployment and must never collide with
a standard SIG service).

| Characteristic | UUID suffix | Properties | Purpose |
|---|---|---|---|
| `DIGEST` | `…6001` | Read, Notify | Peer's Bloom-filtered set of held packet IDs |
| `PACKET_IN` | `…6002` | Write, Write-No-Response | Inbound chunk stream |
| `ACK` | `…6003` | Notify | Per-packet acknowledgement / rejection reason |
| `NODE_INFO` | `…6004` | Read | Device id, protocol version, `has_internet` flag, free slots |

The advertisement payload carries the service UUID plus 4 bytes of manufacturer
data: `[protocol_version:1][flags:1][pending_count:2]`. The `flags` byte's bit 0
is `HAS_INTERNET`. This lets a scanning device **prefer connecting to a peer
that can actually reach the server** — a one-byte optimisation with an
outsized effect on delivery time.

## 6.3 Session flow (anti-entropy)

```mermaid
sequenceDiagram
    participant A as Phone A (central)
    participant B as Phone B (peripheral)

    B->>A: Advertisement (service UUID, flags, pending_count)
    A->>B: GATT connect
    A->>B: Request MTU 517
    B-->>A: MTU granted (e.g. 247)
    A->>B: Read NODE_INFO
    A->>B: Read DIGEST  (Bloom filter of B's packet ids)
    Note over A: A computes: my_packets − B's_digest<br/>= candidate set
    Note over A: filter: ttl_remaining > 0<br/>and not previously sent to B
    loop for each candidate packet
        A->>B: Write chunks 0..n on PACKET_IN
        B-->>A: Notify ACK(packet_id, status)
        Note over B: verify CRC → verify HMAC →<br/>dedup on packet_id → store →<br/>hop_count+1, ttl−1
    end
    A->>B: Disconnect
```

Exchanging a **digest before any payload** means a packet is never transmitted
to a peer that already holds it. This is classic epidemic-routing anti-entropy,
and it is what keeps a dense cluster of phones from saturating the air with
redundant copies.

The digest is a 256-byte Bloom filter with 3 hash functions — false positives
(~1% at 200 packets) cause a packet to be *skipped*, never duplicated, and the
next session with a different filter state will catch it. That failure direction
is the safe one.

## 6.4 Wire format

ATT MTU after negotiation is typically **185–517 bytes**, and an emergency with
a description exceeds that. Packets are therefore chunked.

**Chunk frame** (written to `PACKET_IN`):

```
 0        1        2        3        4                     n
+--------+--------+--------+--------+---------------------+
| VER    | FLAGS  | SEQ    | TOTAL  |  PAYLOAD FRAGMENT   |
+--------+--------+--------+--------+---------------------+
  1 byte   1 byte   1 byte   1 byte    up to (MTU-3-4)
```

- `VER` — protocol version (`0x01`)
- `FLAGS` — bit 0 `FIRST`, bit 1 `LAST`, bit 2 `COMPRESSED`
- `SEQ` / `TOTAL` — fragment index and count (max 255 fragments)
- The **first** fragment's payload begins with the fixed 84-byte packet header
  below; the remainder is the CBOR-encoded emergency body.

**Packet header** (84 bytes, big-endian):

| Offset | Size | Field |
|---|---|---|
| 0 | 1 | `version` |
| 1 | 1 | `ttl_remaining` |
| 2 | 1 | `hop_count` |
| 3 | 1 | `flags` |
| 4 | 16 | `packet_id` (UUID, raw bytes) |
| 20 | 16 | `emergency_id` (UUID, raw bytes) |
| 36 | 16 | `origin_device_id` (UUID, raw bytes) |
| 52 | 8 | `created_at_device` (epoch ms, int64) |
| 60 | 2 | `body_length` |
| 62 | 4 | `crc32` of body |
| 66 | 16 | `hmac` (truncated HMAC-SHA256, first 16 bytes) |
| 82 | 2 | reserved |

Body is CBOR (compact, schema-free, well-supported on Android) containing type
code, description, affected/vulnerability counts, lat/lng/accuracy, and the
life-threatening flag. A typical packet is **220–400 bytes total** — two to
three fragments at a 247-byte MTU.

Reassembly buffers are keyed by `(peer_address, packet_id)` and discarded after
a **10-second** inactivity timeout, so a peer that walks out of range mid-transfer
cannot leak memory.

## 6.5 Receive algorithm

```
on complete reassembly:
    if version unsupported            -> ACK(UNSUPPORTED);  drop
    if crc32 mismatch                 -> ACK(CORRUPT);      drop
    if packet_id in seen_set          -> ACK(DUPLICATE)
                                         log DUPLICATE_SUPPRESSED
                                         drop            # ← loop suppression
    if hmac invalid (key known)       -> ACK(INVALID_HMAC); log; drop
    if ttl_remaining == 0             -> store, mark NOT forwardable
                                         log TTL_EXPIRED
                                         ACK(ACCEPTED_TERMINAL)
    else:
        store packet
        hop_count    += 1
        ttl_remaining -= 1
        mark forwardable
        add packet_id to seen_set
        upsert local emergency row (dedup on emergency_id)
        log RELAY_RECEIVED
        ACK(ACCEPTED)
```

**`packet_id` is never regenerated.** A relay rewrites only `ttl_remaining`,
`hop_count`, and the HMAC-excluded header bytes. If each hop minted a new packet
id, the seen-set would never match and packets would circulate until TTL burned
out — turning an optimisation into a broadcast storm. This is the most important
single line in the specification.

## 6.6 TTL and hop count

Default `ttl_initial = 10` (configurable in `settings`).

| Node | `hop_count` | `ttl_remaining` |
|---|---|---|
| Origin A | 0 | 10 |
| B | 1 | 9 |
| C | 2 | 8 |
| … | … | … |
| Terminal | 10 | 0 |

At `ttl_remaining == 0` the packet is still **stored and still syncable** — it
simply stops being forwarded over BLE. A packet that reaches its last hop on a
phone that later gets signal must still reach the server; discarding it would
throw away a delivered report.

Forwarding eligibility also requires: not previously sent to this peer in this
session, battery above the configured floor (default 15%), and packet age below
the configured maximum (default 24 h).

## 6.7 Integrity: relays cannot tamper

A packet passes through strangers' phones. On registration the server issues each
device a random 32-byte `hmac_key`. The **origin** device signs a canonical
representation of the packet; the server verifies it on ingest.

Relays cannot verify the MAC — they do not hold other devices' keys — so they
carry it opaquely. A tampered payload is stored with `status = INVALID_HMAC` and
surfaced on the command center's network monitoring page rather than silently
dropped, because evidence of tampering is itself operationally interesting.

### 6.7.1 The canonical string — a cross-language contract

The signed bytes are **not** a JSON dump. Kotlin cannot reproduce PHP's
`json_encode` byte-for-byte: PHP escapes forward slashes by default, the two
languages format floating-point differently, and their Unicode escaping rules
diverge. Signing a JSON encoding would mean every real device's packets failing
verification on arrival.

The canonical string is therefore defined explicitly, in a fixed field order:

```
bulig.canon.v1
<text>   packet_id
<text>   emergency_id
<text>   origin_device_id
<text>   created_at_device   (epoch milliseconds, as digits)
<text>   payload.type_code
<text>   payload.description
<int>    payload.affected_count
<int>    payload.children_count
<int>    payload.elderly_count
<int>    payload.mobility_limited_count
<bool>   payload.is_life_threatening
<text>   payload.vulnerability_notes
<text>   payload.latitude          (fixed 7 decimal places)
<text>   payload.longitude         (fixed 7 decimal places)
<text>   payload.accuracy_m        (fixed 2 decimal places)
<text>   payload.location_provider
<text>   payload.captured_at       (epoch milliseconds, as digits)
```

Fields are joined with `\n`. Encoding rules:

| Type | Rule |
|---|---|
| `<text>` | `<utf8ByteLength>:<text>` — length-prefixed |
| `<int>` | decimal digits, no padding |
| `<bool>` | `1` or `0` |
| decimals | rendered to the stated scale, then length-prefixed as text |
| null / absent | the empty text field, `0:` |

**Why length prefixes.** A description containing a newline would otherwise be
able to impersonate a field boundary and let two different packets canonicalise
identically. The prefix makes that impossible, and it is trivial to implement in
both languages.

**Why epoch milliseconds.** Date *formatting* differs between platforms and
locales; an instant does not. `2026-08-27T03:52:11Z` and `2026-08-27T11:52:11+08:00`
are the same packet and must produce the same signature.

**Why coordinates are fixed-scale.** Seven decimal places is roughly 11 mm —
far beyond any phone GPS. Devices must not send more precision than that.

The MAC is `HMAC-SHA256(device_key, canonical)`, truncated to the first **32 hex
characters** (16 bytes) for the wire header.

### 6.7.2 What is excluded, and why

`ttl_remaining` and `hop_count` are **deliberately outside** the signed bytes.
Relays must be able to decrement them without invalidating the origin's
signature — that is precisely what lets an untrusted phone carry a packet it
cannot forge.

### 6.7.3 The shared test vector

One fixture is asserted in **both** suites — `CanonicalPacketTest` in PHP and
`CanonicalPacketTest.kt` in Kotlin — with the same expected MAC. It deliberately
includes a description containing a newline, a forward slash and non-ASCII
characters, a null field, a negative coordinate, and an accuracy value requiring
rounding.

If the two implementations ever drift apart, a test goes red. Without this
vector, the failure mode is silent: the backend's own tests would still pass
(they would share the bug), and the problem would surface only as "the mesh
doesn't work" during device testing.

### 6.7.4 What this does not provide

Authentication of origin and detection of modification — not confidentiality.
Payloads are **not encrypted** between devices; a BLE sniffer in range could read
a report in transit. Documented in `LIMITATIONS.md` as future work, not claimed
as a feature.

## 6.8 Android platform realities

| Constraint | Handling |
|---|---|
| Android 12+ requires `BLUETOOTH_SCAN` / `ADVERTISE` / `CONNECT` | Runtime permission flow with a plain-language rationale screen |
| Scan results throttled when the screen is off | Foreground service, `ScanSettings.SCAN_MODE_LOW_LATENCY` during active windows only |
| Background execution limits | `ForegroundServiceType` `connectedDevice` + `location`; persistent notification |
| Some devices cap concurrent GATT connections (often 4–7) | Connection pool with a hard cap of 3 and LRU eviction |
| Manufacturer battery managers kill services | Onboarding prompts the user to exempt Bulig from battery optimisation |
| Advertising unsupported on some older chipsets | Detected at startup; device degrades to **central-only** and the UI says so honestly |
| BLE range is roughly 10–30 m and is degraded by walls, bodies, rain, and 2.4 GHz congestion | Never claimed otherwise in the UI or the paper |

## 6.9 Testability without radios

Relay, dedup, TTL, and forwarding-eligibility logic live in `:core-mesh`, a pure
Kotlin module with **no Android dependencies**, behind a `MeshTransport`
interface. A `VirtualMesh` fake wires N in-memory nodes with a configurable
topology, loss rate, and latency, so proposal TESTS 3, 4, and 5 run as
deterministic JVM unit tests in milliseconds.

Real-device testing then validates the *radio*; the automated suite validates the
*protocol*. Both belong in the defense.
