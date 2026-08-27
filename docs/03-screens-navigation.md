# 03 — Screens and Navigation

> Final visual design is being produced separately in Claude Design. This
> document fixes **structure, hierarchy, and content** — the layer that must be
> correct before pixels. The command center is implemented with semantic theme
> tokens so a finished visual design can be applied without touching logic.

## 3.1 Design principles (§27)

Modern, clean, professional, emergency-response focused. High readability under
stress. Large primary actions. Unambiguous status. Strong hierarchy. Minimal
decoration. Not a generic CRUD school project.

Two rules specific to this domain:

- **Stress legibility.** A person reporting an emergency is panicking, possibly
  in the dark, possibly with wet hands. The primary action is oversized, high
  contrast, and reachable one-handed.
- **Honest status.** Connectivity and delivery state are always visible and never
  overstated. "Relayed" never renders as "delivered."

## 3.2 Android — Resident

| # | Screen | Content |
|---|---|---|
| R1 | Splash / permission onboarding | BLE + location + battery-exemption rationale in plain language |
| R2 | **Home** | Persistent status banner; giant `REPORT EMERGENCY` button; "My reports" list; nearby-peers count |
| R3 | Emergency type picker | Large icon grid, configurable types, Waray-Waray + English labels |
| R4 | Report details | Description, affected count, children/elderly/mobility-limited steppers, life-threatening toggle |
| R5 | Location confirm | Map preview, accuracy ring, "adjust pin" fallback for poor GPS |
| R6 | Review & submit | Summary + computed priority with reasons; one confirm action |
| R7 | Submitted confirmation | Emergency code, current delivery state, plain-language next steps |
| R8 | My reports | Per-report delivery state chips (PENDING / RELAYED / SYNCED) |
| R9 | Report detail | Timeline: created → relayed via N phones → synced → assigned → resolved |
| R10 | Mesh status | Nearby Bulig devices, packets carried for others, hops, TTL remaining |
| R11 | Settings | Account, language, relay participation, battery, about |

**R2 is the whole app.** Report creation must be reachable in one tap from
launch, and R3→R6 must be completable in under 60 seconds — that is the
"emergency creation time" metric in §24.

**R10 exists for a specific reason.** It makes the invisible mesh visible: a
resident can see that their phone is carrying three neighbours' reports. It is
also the screen that demonstrates the project's contribution during a defense.

## 3.3 Android — Responder

Same APK, role-gated navigation.

| # | Screen | Content |
|---|---|---|
| P1 | **Assignments** | Active assignments sorted by priority; type, priority chip, distance, age, status |
| P2 | Assignment detail | Type, description, location + map, affected/vulnerability breakdown, priority reasons, reporter contact where permitted |
| P3 | Action bar | `ACCEPT` → `EN ROUTE` → `ON SITE` → `RESOLVED`, plus `DECLINE` with reason |
| P4 | Navigate | Hand off to a maps intent; offline fallback shows bearing + distance |
| P5 | Resolution notes | Outcome, persons assisted, optional notes |
| P6 | History | Past assignments |

Status actions queue offline and sync later, exactly like reports. A responder in
a dead zone can still record arriving on site.

## 3.4 Web — Barangay Command Center (§18)

```
Dashboard
├── Emergency Map
├── Incident Queue ──▶ Incident Detail
├── Responders
├── Rescue Teams
├── Assignments
├── Network
│   ├── Packet Monitoring
│   └── Synchronisation Logs
├── Reports
└── Administration
    ├── Users
    ├── Emergency Types
    ├── Settings  (incl. priority scoring config)
    └── Audit Logs
```

### Dashboard layout (§17)

```
┌──────────────────────────────────────────────────────────────────┐
│ ● SYSTEM ONLINE   Active 12 │ CRITICAL 3 │ Pending sync 5 │ Devices 18 │
├────────────────────────────────────┬─────────────────────────────┤
│ INCIDENT QUEUE                     │                             │
│ ┌────────────────────────────────┐ │        LEAFLET MAP          │
│ │ CRITICAL · BLG-2026-0417       │ │   priority-coloured markers │
│ │ Medical · 4 affected · 2 min   │ │   click → detail panel      │
│ │ ● unassigned                   │ │                             │
│ ├────────────────────────────────┤ │                             │
│ │ HIGH · BLG-2026-0416           │ │                             │
│ │ Fire · 12 affected · 14 min    │ │                             │
│ │ ● Team Alpha · EN ROUTE        │ │                             │
│ └────────────────────────────────┘ │                             │
└────────────────────────────────────┴─────────────────────────────┘
```

Queue and map are linked selections: hovering a row highlights its marker and
vice versa. Livewire polling keeps both live without a page reload.

### Incident Detail must show (§17)

Emergency code · type · description · affected + vulnerability breakdown ·
location with accuracy · time reported (device *and* server) · **priority with
its rule trace** · status · assigned responder/team · full timeline · and the
**routing evidence**: which devices carried it, how many hops, TTL remaining on
arrival, duplicates suppressed.

That last block is unique to this project. It is what turns "an incident page"
into evidence that the mesh worked.

### Packet Monitoring

Live packet table, hop-count distribution, transmission-delay percentiles,
duplicate suppression count, TTL-expiry count, per-device relay contribution,
HMAC failures. This page *is* the evaluation data for §24 metrics 2–8.

## 3.5 Empty, loading, and failure states

Every list defines all four states. Two matter especially:

- **No incidents:** "No active emergencies." — reassuring, not an error.
- **Server unreachable (web):** an explicit banner. The command center is an
  online application; pretending otherwise would contradict the honesty the
  mobile app is built on.

## 3.6 Accessibility

Minimum 16sp/16px body text, 48dp touch targets, WCAG AA contrast, priority
never encoded by colour alone (colour + label + icon), full screen-reader
labelling on the report flow, and Waray-Waray alongside English on
resident-facing labels.
