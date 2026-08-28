# Design Reconciliation

Where the design handoff (`HANDOFF.md`) and the implemented system agree, disagree,
and where each one wins. Written when the mobile design bundle arrived, against a
backend and mesh engine that were already built and tested.

The handoff states that where it and the prototype HTML disagree, the handoff
wins. This document covers the next layer down: where the handoff and the
**already-implemented, tested system** disagree.

---

## 1. Priority levels — the design's examples are demo data, not a rule spec

**Status: engine wins. Design examples treated as illustrative.**

The handoff specifies categorical rules (§ "Priority computation"). The backend
implements the weighted, configurable scoring formula that proposal §9 requires,
documented in `docs/08-priority-engine.md` and covered by 11 unit tests.

Checking the design's four "My reports" cards against the engine:

| Design card | Design shows | Engine gives | |
|---|---|---|---|
| Flood · life-threatening · 5 affected, 2 children, 1 elderly, 1 mobility-limited | CRITICAL | CRITICAL (76) | match |
| Infrastructure damage · no detail shown | HIGH | LOW (15) | **diverges** |
| Missing person | MODERATE | MODERATE (25) | match |
| Medical | LOW | HIGH (35) | **diverges** |

Two of four diverge — but the design's own stated rules also contradict its own
cards:

- Its LOW rule is *"report only, no injury, no vulnerability"*, which is exactly
  the infrastructure card it renders as HIGH.
- Its HIGH rule includes *"injury implied by type"*, which is exactly the medical
  card it renders as LOW.

So the four cards were chosen to display all four chip styles, not derived from a
rule set. Read as a rule specification they are inconsistent; read as demo data
they are fine.

**Resolution.** Keep the scoring engine: it is what the proposal requires, it is
configurable per the handoff's own escape hatch (*"make the rule table
configurable — but keep the reasons visible"*), and it is already tested. Adopt
the handoff's genuine requirement — that **reasons are shown, never just a
coloured word** — which the engine already supports via
`priority_breakdown.factors`.

**Action for the demo:** seed data should be chosen so the four priority levels
still all appear on screen. Do not hand-set levels to match the mock.

---

## 2. The hop log shows information an offline phone cannot have

**Status: design is internally inconsistent. Implemented the honest version.**

Artboard 08 is in the **RELAYED — NOT YET DELIVERED** state, so by the design's
own rules the command center has not confirmed anything. Yet it renders:

```
hop 1 · phone-7C4A · 15:04
hop 2 · phone-B119 · 15:06
hop 3 · phone-2E80 · 15:07
```

A phone cannot know hops 2 and 3. When A hands a report to B, A observes exactly
one fact: *B took a copy at 15:04*. There is no back-channel through an offline
mesh, so B's later handoff to C is invisible to A until the server confirms
delivery and reports the route back.

This is the same class of error the design exists to prevent — a screen claiming
more than it knows — appearing inside the design itself.

**Implemented:** `Handoff(peerId, atMs)` records only handoffs a device performed
itself, with times it actually observed. `MeshNode.handoffsFor()` returns them;
`handoffCount()` gives the number of peers that took a copy **from this phone**.
Covered by `a device knows only its own handoffs never the full route`.

**Consequence for the UI.** Two different true statements are available, and they
must not be conflated:

| Claim | Knowable offline? | Wording |
|---|---|---|
| N peers took a copy from this phone | yes | "3 phones took a copy" |
| The report travelled N hops | only after delivery | "reached the command center after 3 hops" |

Artboard 08's banner ("Passed to 3 nearby phones") is fine — that is the first
claim. The hop *log* beneath it is the second, and should either show only
directly-witnessed handoffs while undelivered, or appear only once the server has
returned the true route.

---

## 3. Emergency type copy — design wins, but the Waray needs a native speaker

**Status: adopted verbatim. Flagged for review.**

`EmergencyTypeSeeder` now carries the handoff's labels and Waray-Waray strings
exactly. The handoff itself says to treat them as *"reviewed placeholders"* and
have a native speaker check them.

That review is not optional for this project, because at least three strings do
not read as Waray-Waray:

| Type | In the design | Concern |
|---|---|---|
| Landslide | `Pagdahili sang tuna` | `sang` is a Hiligaynon genitive marker; Waray uses `han`/`hin` |
| Description sample | `Duha ka bata ug usa ka lola sa atop` | `ug` and `ka` are Cebuano; Waray would use `ngan` and `nga` |
| Missing / Trapped | `Nawawara nga tawo`, `Nakukulong nga tawo` | plausible, but the aspect marking should be confirmed |

The pilot barangay is in Tacloban, where the panel and the residents are Waray
speakers. Shipping Cebuano or Hiligaynon strings labelled as Waray is the kind of
detail that undermines a capstone's credibility in the room — and matters more
than that for a resident reading under stress.

**Action:** get every Waray string reviewed before the pilot. Until then they stay
marked `TO BE VALIDATED` in the seeder.

---

## 4. Delivery state — adopted, and now enforced by tests

**Status: design wins entirely.**

The handoff's central rule is implemented as
`ph.bulig.mesh.delivery.DeliveryState` with a single formatter, exactly as asked:

> Give it one formatter that returns chip color, icon, label, and the
> plain-language sentence together, so no screen can accidentally render a partial
> or optimistic version.

Rather than a raw colour, the formatter returns a `DeliveryTone`
(`NEUTRAL` / `IN_MOTION` / `CONFIRMED`), so the "green is reserved" rule becomes a
property a test can check rather than a convention a developer must remember.
`DeliveryHonestyTest` asserts:

- no undelivered state may ever render in the confirmed tone
- `RELAYED` always carries the negation, in both chip and banner
- a locally-held report never uses the words "sent", "delivered" or "received"
- state can only move forward, so a late mesh event cannot drag a resolved report
  backwards
- no number of hops turns relayed into delivered
- banner states sharing a hue stay distinguishable by icon and wording

That last one matters because `OFFLINE`/`PENDING` and `SYNCING`/`RELAYED` share
colours by design; the test fails if a future edit lets either pair collapse.

---

## 5. Device pseudonyms — adopted, with the stable id kept underneath

**Status: design wins, with an implementation note.**

The handoff asks for device names that are *"random and change daily"*. The
underlying `DeviceId` cannot rotate: the server issues a signing key against it,
and the mesh's seen-set depends on stable identity.

**Implemented:** `PeerPseudonym.forDevice(deviceId, dayEpoch)` derives the
displayed `phone-XXXX` name from the stable id plus the day. Stable within a day,
different the next, and not a substring of the real id. Tested.

---

## 6. Encryption at rest — a new requirement

**Status: accepted, not yet built.**

Artboard 06 tells the resident *"your report sits on this phone, encrypted"*, and
the handoff requires local storage "encrypted at rest".

Nothing in the system does this yet, and `LIMITATIONS.md` currently discloses only
that the **wire** is unencrypted. When the `:data` layer is built it needs
SQLCipher or the platform equivalent — otherwise artboard 06 makes a claim the app
does not honour, which is the one thing this design says never to do.

Tracked as an open item, not silently absorbed.

---

## 7. Two state vocabularies, deliberately kept separate

The handoff's `deliveryState` merges device-side and server-side progress into one
enum. The backend keeps them apart: device states (`SAVED_LOCAL`…`SYNCED`) and
incident states (`NEW`…`RESOLVED`), joined at delivery.

They are kept separate on purpose — an offline device cannot know about triage,
assignment, or resolution, so it must not have a vocabulary that implies it does.
The Kotlin `DeliveryState` enum spans both because it describes what the
**resident** sees about their own report, which is exactly the handoff's framing.
The mapping is one-directional: incident status flows down into delivery state on
sync, never the reverse.

---

## Summary

| Item | Winner | Status |
|---|---|---|
| Priority rules | implemented engine | design cards are demo data |
| Hop log | neither as written | honest version implemented |
| Type copy | design | adopted; Waray needs native review |
| Delivery state + formatter | design | adopted and test-enforced |
| Green is reserved | design | enforced by `DeliveryHonestyTest` |
| Device pseudonyms | design | adopted over a stable id |
| Encryption at rest | design | accepted, open item |
| State vocabularies | implemented split | documented rationale |
