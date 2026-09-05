# Design brief: BULIG system diagrams

For Claude Design. Three diagrams for a 4th-year IT capstone defense in the
Philippines: **use case**, **data (ERD)**, and **system flowchart** — plus two
optional DFD levels most PH panels also ask for.

Every actor, entity, relationship, and decision below is taken from the
**implemented and tested system**, not invented. Do not add elements that are not
listed here; a panel will ask about anything on the page, and the student must be
able to point to the code behind it.

---

## What the system is, in one paragraph

BULIG (Waray-Waray for "help") lets a resident of a Tacloban barangay file an
emergency report with **no internet and no cell signal**. The report is written to
the phone's own storage, then relayed phone-to-phone over Bluetooth Low Energy
until some phone in the mesh finds signal and uploads it to a Laravel/MySQL
server, where a barangay operator triages it and assigns a responder.

**The thing every diagram must make obvious:** creating, storing, and relaying a
report require no internet. Internet is required only at the *last* hop. If a
diagram could be redrawn as a normal client-server app without losing meaning,
it has failed.

---

## Visual system

Reuse the tokens from the mobile UI handoff so the diagrams read as the same
product. Colours in `oklch()`, hex given for tools without oklch support.

| Role in diagrams | Token | oklch | ~hex |
|---|---|---|---|
| Page background | `canvas` | `oklch(0.98 0.004 250)` | `#F8F9FB` |
| Node fill | `surface` | `oklch(1 0 0)` | `#FFFFFF` |
| Node border, connectors | `border` | `oklch(0.90 0.008 250)` | `#DFE2E8` |
| Primary text | `ink` | `oklch(0.22 0.015 260)` | `#25272E` |
| Secondary text, edge labels | `ink-muted` | `oklch(0.50 0.014 260)` | `#6E717A` |
| **Offline / mesh path** | `offline` | `oklch(0.70 0.17 65)` | `#E28A1B` |
| **Online / server path** | `brand-strong` | `oklch(0.44 0.17 252)` | `#2A5AC4` |
| Confirmed / delivered | `online` | `oklch(0.62 0.16 150)` | `#2E9E63` |
| Critical, danger, drop | `danger` | `oklch(0.55 0.22 25)` | `#D2352A` |

**Type:** Inter (400/500/600/700). **IBM Plex Mono** for table names, field names,
and cardinality markers only.

**One colour rule carries the whole thesis:** every edge that works **without
internet** is drawn in `offline` amber; every edge that **requires internet** is
drawn in `brand-strong` blue. A panelist should be able to see, from three metres
away, that the amber region is most of the diagram. Include a legend saying
exactly this on every diagram that uses both.

Nodes are flat: `surface` fill, 1px `border`, radius 12. No drop shadows, no
gradients, no 3D. This is an engineering document.

---

# Diagram 1 — Use Case Diagram (UML)

Standard UML: actors as stick figures outside a labelled system boundary,
use cases as ellipses inside it, `<<include>>` and `<<extend>>` as dashed arrows
with open arrowheads.

**System boundary label:** `BULIG — Barangay Emergency Communication System`

## Actors

Place **primary** actors on the left, **supporting** actors on the right.

**Left (primary):**

1. **Resident / Reporter** — files reports; every install also relays
2. **Responder** *(Barangay Tanod / BDRRMC)* — accepts and works assignments
3. **Barangay Operator** — triages the queue, assigns responders
4. **Barangay Official** — manages staff, teams, and settings
5. **System Administrator** — accounts, database, audit

**Right (supporting / system actors):**

6. **Nearby BULIG Device** — a peer phone in the mesh
7. **GPS / Location Service**

Draw **Responder inheriting from Resident** (hollow-triangle generalisation
arrow, Responder → Resident): a responder's phone files and relays reports
exactly like any other. Same for **Official → Operator**. This is real in the
code — one app, role-gated navigation — and it saves a dozen duplicate lines.

## Use cases, grouped

Lay out in four visually grouped clusters inside the boundary. Do not draw the
group boxes as UML packages; just cluster them spatially with a small uppercase
label above each.

**REPORTING** *(Resident)*
- Create Emergency Report
- Select Emergency Type
- Capture GPS Location
- Record Affected & Vulnerable Persons
- View My Reports
- Track Report Delivery Status

**MESH RELAY** *(Resident, Nearby BULIG Device)*
- Discover Nearby Devices
- Exchange Packet Digest
- Relay Emergency Packet
- Receive & Store Packet
- Suppress Duplicate Packet
- Synchronise Pending Packets

**COORDINATION** *(Operator, Official, Responder)*
- View Incident Queue
- View Emergency Map
- View Incident Detail
- Override Priority
- Assign Responder
- Accept / Decline Assignment
- Update Incident Status
- View Packet & Network Monitoring

**ADMINISTRATION** *(Official, System Administrator)*
- Manage Users & Roles
- Manage Rescue Teams
- Manage Emergency Types
- Configure Priority Rules
- View Audit Logs
- Generate Reports

## Relationships to draw

**`<<include>>`** (dashed, arrow points to the included case):

| Base case | includes |
|---|---|
| Create Emergency Report | Select Emergency Type |
| Create Emergency Report | Capture GPS Location |
| Create Emergency Report | Compute Priority |
| Relay Emergency Packet | Exchange Packet Digest |
| Receive & Store Packet | Suppress Duplicate Packet |
| Assign Responder | View Incident Detail |
| Override Priority | Record Audit Entry |
| Assign Responder | Record Audit Entry |

**`<<extend>>`** (dashed, arrow points *back* to the base case):

| Extension | extends | condition |
|---|---|---|
| Adjust Pin by Hand | Capture GPS Location | *{GPS unavailable or inaccurate}* |
| Record Voice Note | Create Emergency Report | *{resident cannot type}* |
| Decline Assignment | Accept / Decline Assignment | *{responder cannot reach}* |
| Escalate Incident | Update Incident Status | *{needs more help}* |

Add **Compute Priority** and **Record Audit Entry** as use cases inside the
boundary with no direct actor line — they are only ever reached via `<<include>>`.

## Actor → use case lines

Keep these clean; a use case reached only through `<<include>>` gets no actor
line.

- **Resident** → Create Emergency Report · View My Reports · Track Report
  Delivery Status · Discover Nearby Devices · Relay Emergency Packet ·
  Synchronise Pending Packets
- **Responder** → Accept / Decline Assignment · Update Incident Status ·
  View Incident Detail *(plus everything inherited from Resident)*
- **Operator** → View Incident Queue · View Emergency Map · View Incident Detail ·
  Override Priority · Assign Responder · Update Incident Status ·
  View Packet & Network Monitoring · Generate Reports
- **Official** → Manage Users & Roles · Manage Rescue Teams ·
  Manage Emergency Types · Configure Priority Rules · View Audit Logs
  *(plus everything inherited from Operator)*
- **System Administrator** → Manage Users & Roles · View Audit Logs
- **Nearby BULIG Device** → Relay Emergency Packet · Receive & Store Packet ·
  Exchange Packet Digest
- **GPS / Location Service** → Capture GPS Location

## What this diagram must prove

That **Nearby BULIG Device is an actor at all**. In a conventional emergency app
there is no such actor — the phone talks only to a server. Its presence, and the
Mesh Relay cluster it connects to, is the entire contribution. Draw those actor
lines in `offline` amber; draw the Coordination and Administration lines in
`brand-strong` blue.

---

# Diagram 2 — Entity Relationship Diagram

Crow's-foot notation. 14 domain tables, taken verbatim from the implemented
schema. Do **not** include Laravel's framework tables (`cache`, `jobs`,
`personal_access_tokens`) — they are infrastructure, not domain.

Each entity box: table name in **mono 600**, primary key first, then columns,
then foreign keys. Mark `PK`, `FK`, and `UQ` in a small right-aligned mono
column.

## Entities and their key columns

Show only these columns — the full dictionary lives in `docs/04-database-erd.md`
and a diagram that reproduces it becomes unreadable.

| Entity | Columns to show |
|---|---|
| `users` | id `PK` · name · email `UQ` · role · is_active |
| `devices` | id `PK` · device_id `UQ` · user_id `FK` · hmac_key · is_revoked · last_seen_at |
| `emergency_types` | id `PK` · code `UQ` · label_en · label_war · base_severity |
| `emergencies` | id `PK` · **emergency_id `UQ`** · emergency_code `UQ` · emergency_type_id `FK` · description · affected_count · children_count · elderly_count · mobility_limited_count · is_life_threatening · priority_level · priority_score · priority_breakdown · status · origin_device_id `FK` · created_at_device · received_at_server · first_hop_count |
| `emergency_locations` | id `PK` · emergency_id `FK UQ` · latitude · longitude · accuracy_m · provider |
| `emergency_packets` | id `PK` · **packet_id `UQ`** · emergency_uuid · origin_device_id `FK` · current_device_id `FK` · hop_count · ttl_remaining · ttl_initial · hmac · hmac_valid · status · clock_offset_ms |
| `packet_logs` | id `PK` · packet_id · sync_log_id `FK` · device_id `FK` · event · hop_count · ttl_remaining · occurred_at |
| `sync_logs` | id `PK` · device_id `FK` · direction · packets_sent · packets_accepted · packets_duplicate · outcome · duration_ms |
| `rescue_teams` | id `PK` · name · code `UQ` · base_location · is_active |
| `responders` | id `PK` · user_id `FK UQ` · rescue_team_id `FK` · badge_no · status |
| `rescue_assignments` | id `PK` · emergency_id `FK` · responder_id `FK` · rescue_team_id `FK` · assigned_by_user_id `FK` · status · assigned_at · accepted_at · resolved_at |
| `status_history` | id `PK` · emergency_id `FK` · from_status · to_status · changed_by_user_id `FK` · source · occurred_at |
| `audit_logs` | id `PK` · user_id `FK` · action · subject_type · subject_id · before · after · occurred_at |
| `settings` | id `PK` · key `UQ` · value · group |

## Relationships

| From | To | Cardinality | Label |
|---|---|---|---|
| `users` | `emergencies` | 1 : 0..N | reports |
| `users` | `devices` | 1 : 0..N | owns |
| `users` | `responders` | 1 : 0..1 | may be |
| `users` | `audit_logs` | 1 : 0..N | performs |
| `devices` | `emergencies` | 1 : 0..N | originates |
| `devices` | `emergency_packets` | 1 : 0..N | carries |
| `devices` | `sync_logs` | 1 : 0..N | synchronises |
| `emergency_types` | `emergencies` | 1 : 0..N | classifies |
| `emergencies` | `emergency_locations` | 1 : 1 | located at |
| `emergencies` | `emergency_packets` | 1 : 0..N | transported by |
| `emergencies` | `rescue_assignments` | 1 : 0..N | assigned via |
| `emergencies` | `status_history` | 1 : 0..N | transitions |
| `emergency_packets` | `packet_logs` | 1 : 0..N | events |
| `sync_logs` | `packet_logs` | 1 : 0..N | batch contains |
| `rescue_teams` | `responders` | 1 : 0..N | staffed by |
| `rescue_teams` | `rescue_assignments` | 1 : 0..N | receives |
| `responders` | `rescue_assignments` | 1 : 0..N | acts on |

## Two annotations that must appear

These are the two design decisions a panel is most likely to probe. Draw each as
a small callout box in `offline` amber, connected by a thin leader line.

**On `emergencies.emergency_id` and `emergency_packets.packet_id`:**

> UUIDs minted **on the device**, not by the server. A phone with no connectivity
> cannot ask a server for an identifier, so the device owns identity and the
> server enforces uniqueness on it.

**On the `emergencies` ↔ `emergency_packets` link:**

> Linked by business key `emergency_uuid`, **not** a foreign key. Packets can
> reach the server before the emergency row exists — multi-hop delivery arrives
> out of order. Reconciled on ingest.

Draw that one relationship line **dashed** to distinguish it from the hard FKs.

## Layout

Three horizontal bands, top to bottom:

1. **Identity** — `users`, `devices`, `settings`
2. **Emergency core** — `emergency_types`, `emergencies`, `emergency_locations`
3. **Mesh transport** *(tint this band `offline` at 6%)* — `emergency_packets`,
   `packet_logs`, `sync_logs`
4. **Response** — `rescue_teams`, `responders`, `rescue_assignments`,
   `status_history`, `audit_logs`

Tinting the mesh band is deliberate: it shows at a glance that a third of the
schema exists purely to make offline delivery work and auditable.

---

# Diagram 3 — System Flowchart

**This is the most important diagram in the set.** Standard ANSI symbols:
rounded rectangle = terminator, rectangle = process, diamond = decision,
parallelogram = input/output, cylinder = stored data, rectangle with double side
bars = predefined process, circle = on-page connector.

Portrait orientation. Three vertical **swimlanes**:

| Lane | Header | Tint |
|---|---|---|
| A | **RESIDENT'S PHONE** *(no internet)* | `offline` at 5% |
| B | **NEARBY PHONES — BLE MESH** *(no internet)* | `offline` at 8% |
| C | **SERVER & COMMAND CENTER** *(internet)* | `brand-strong` at 5% |

The lane tints alone should tell the story: two of three lanes run with no
connectivity.

## Flow

**Lane A — Resident's phone**

1. `TERMINATOR` — **Start: resident opens BULIG**
2. `INPUT` — Select emergency type, details, affected & vulnerable persons
3. `PROCESS` — Capture GPS fix
4. `DECISION` — **GPS available?**
   - *No* → `INPUT` Adjust pin by hand / confirm purok → rejoin
   - *Yes* ↓
5. `PROCESS` — Mint `emergency_id` + `packet_id` (UUIDv4, on device)
6. `PREDEFINED PROCESS` — **Compute priority** *(rule-based, on device)*
7. `PROCESS` — Sign packet with device HMAC key
8. `STORED DATA` — **Write to local Room / SQLite**
9. `OUTPUT` — **Show "SAVED ON THIS PHONE"** to resident

   > Callout in `danger`: **No network has been touched yet.** The resident is
   > confirmed at this point. Everything after this is best-effort.

10. `DECISION` — **Internet available?**
    - *Yes* → jump via connector **①** to Lane C step 20
    - *No* ↓ enter relay mode

**Lane B — Mesh relay** *(all edges amber)*

11. `PROCESS` — Advertise BULIG service · scan for peers
12. `DECISION` — **Peer found in range?**
    - *No* → `PROCESS` Wait / duty-cycle → loop back to 11
    - *Yes* ↓
13. `PROCESS` — Exchange Bloom-filter digest of held packet IDs
14. `DECISION` — **Peer already holds this packet?**
    - *Yes* → `PROCESS` Skip transfer → back to 12
    - *No* ↓
15. `PROCESS` — Transfer packet in chunked BLE frames (CRC-checked)
16. `DECISION` — **`packet_id` already in receiver's seen-set?**
    - *Yes* → `PROCESS` **Drop duplicate, log suppression** *(fill `danger` at 8%)* → back to 12
    - *No* ↓
17. `PROCESS` — Store packet · `hop_count + 1` · `ttl_remaining − 1`
18. `DECISION` — **TTL > 0?**
    - *No* → `PROCESS` **Stop forwarding — packet still stored and still syncable**
      *(callout: a terminal packet is not a discarded packet)* → to 19
    - *Yes* → back to 11 *(this phone now relays it onward)*
19. `DECISION` — **This phone has internet?**
    - *No* → back to 11
    - *Yes* ↓ connector **①**

**Lane C — Server** *(all edges blue)*

20. `PROCESS` — `POST /api/v1/sync/packets` *(batched, idempotent)*
21. `PROCESS` — Verify HMAC signature
22. `DECISION` — **Signature valid?**
    - *No* → `STORED DATA` Store as `INVALID_HMAC`, surface on network monitor → to 27
    - *Yes* ↓
23. `DECISION` — **`packet_id` already ingested?**
    - *Yes* → `PROCESS` Mark duplicate, log, **no new incident** → to 27
    - *No* ↓
24. `DECISION` — **`emergency_id` already known?**
    - *Yes* → `PROCESS` Record additional route only — **never overwrite operator state** → to 27
    - *No* ↓
25. `PROCESS` — Create incident · assign `emergency_code` · recompute priority
26. `STORED DATA` — **MySQL**
27. `PROCESS` — Return per-packet result to device
28. `OUTPUT` — Incident appears on **command centre dashboard + map**
29. `PROCESS` — Operator triages, reviews priority reasoning
30. `PROCESS` — Assign responder / team
31. `OUTPUT` — Responder receives assignment via `GET /sync/pull`
32. `PROCESS` — Accept → En route → On site → Resolved
33. `STORED DATA` — Status history + audit log written
34. `TERMINATOR` — **End: incident resolved**

## Emphasis

Three elements get visual weight above everything else:

- **Step 9** (`SAVED ON THIS PHONE`) — the moment the resident is confirmed,
  with no network involved.
- **Step 16** (duplicate drop) — loop suppression, the reason the mesh does not
  become a broadcast storm.
- **Step 18** (TTL exhausted but *still syncable*) — the subtle correctness point
  most implementations get wrong.

Give each a slightly heavier border and a short callout. Everything else stays
uniform.

## Legend

Bottom-left, always visible:

```
──── amber   works with NO internet
──── blue    requires internet
▭ process   ◇ decision   ▱ input/output   ⬭ terminator   ⛁ stored data
```

---

# Optional — DFD, if the panel asks for it

Many PH capstone panels expect a Context Diagram and a Level-1 DFD alongside the
ERD. Include these only if the adviser wants them; they overlap heavily with the
flowchart above.

## Context Diagram (Level 0)

One process bubble: **`0 — BULIG System`**. External entities around it:

| Entity | → System | System → |
|---|---|---|
| Resident | emergency report, location, affected counts | emergency code, delivery status |
| Nearby BULIG Device | relayed packet | forwarded packet, packet digest |
| Responder | assignment response, status update | assignment details, incident location |
| Barangay Operator | triage decision, responder assignment | incident queue, map, priority reasoning |
| Barangay Official | user / team / rule configuration | reports, audit trail |
| GPS Service | coordinates, accuracy | — |

## Level-1 DFD

Five processes, numbered:

1. **Capture Emergency Report** — Resident → `D1 Local Report Store`
2. **Relay Packet over Mesh** — `D1` ↔ Nearby Device ↔ `D2 Packet Store`
3. **Synchronise & Validate** — `D2` → `D3 Emergencies`, `D4 Packet Logs`
4. **Triage & Assign** — `D3` ↔ Operator → `D5 Assignments`
5. **Respond & Resolve** — `D5` ↔ Responder → `D6 Status History`, `D7 Audit Log`

Data stores: `D1` and `D2` sit **on the phone**; `D3`–`D7` sit **on the server**.
Draw a labelled dashed boundary between them reading
**"connectivity boundary — crossed only when signal exists"**. That line is the
whole system in one stroke.

---

## Artboard sizes

| Diagram | Size | Orientation |
|---|---|---|
| Use case | 1920 × 1280 | landscape |
| ERD | 1920 × 1440 | landscape |
| System flowchart | 1400 × 2400 | portrait, 3 swimlanes |
| Context diagram *(optional)* | 1600 × 1000 | landscape |
| Level-1 DFD *(optional)* | 1920 × 1200 | landscape |

Every diagram carries a footer in `ink-subtle` 11px:

> BULIG — Offline-First Emergency Communication and Disaster Response
> Coordination System · Barangay 88, Tacloban City · Capstone prototype

---

## What not to do

- **Do not add elements not listed here.** Every box must map to real code.
- **Do not draw the mesh as a cloud or a mysterious blob.** It is specific:
  discover → digest exchange → chunked transfer → dedup → TTL decrement → forward.
- **Do not use green anywhere except confirmed server receipt.** Green is
  reserved across this whole product, diagrams included.
- **Do not imply guaranteed delivery.** No arrow should read as a promise. If an
  edge can fail, the diagram should not suggest otherwise.
- No 3D, no gradients, no shadows, no clip art, no icons-as-decoration.
