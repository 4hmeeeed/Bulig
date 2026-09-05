# 08 — Emergency Priority Engine

Deterministic, configurable, and **explainable**. No machine learning: when the
panel asks "why is this CRITICAL?", the answer is a rule trace, not a weight
vector.

## 8.1 Where it runs

The same formula is implemented twice — Kotlin on the device, PHP on the server —
driven by identical configuration in `settings.priority_config`.

- **On device:** so a resident sees a priority immediately, offline, and so a
  relay can prefer forwarding higher-priority packets first. Implemented in
  `android/core-mesh/.../priority/PriorityEngine.kt`.
- **On server:** authoritative. Recomputed on ingest. If the device and server
  results disagree (stale config on an old app build), the server value wins and
  the divergence is logged — which is itself a useful reliability metric.

## 8.2 Scoring

`score = base_severity + Σ(factor contributions)`, clamped to 0–100.

| Factor | Rule | Points |
|---|---|---|
| Base severity | from `emergency_types.base_severity` | 10–40 |
| Life-threatening asserted | `is_life_threatening = true` | +25 |
| Affected persons | 1 → +0 · 2–5 → +5 · 6–10 → +10 · 11–25 → +15 · >25 → +20 | 0–20 |
| Children involved | +4 per child, capped | 0–12 |
| Elderly involved | +3 per elderly person, capped | 0–9 |
| Mobility-limited involved | +5 per person, capped | 0–15 |
| Report age | +2 per hour unresolved, capped | 0–10 |
| Multi-hop arrival | `first_hop_count ≥ 2` (implies a connectivity dead zone) | +3 |

Default `base_severity`: Medical 35 · Fire 40 · Trapped Person 40 · Flood 30 ·
Landslide 35 · Earthquake 35 · Rescue Needed 30 · Missing Person 25 ·
Infrastructure Damage 15 · Other 10.

**Bands**

| Score | Level |
|---|---|
| 0–24 | LOW |
| 25–44 | MODERATE |
| 45–69 | HIGH |
| 70–100 | CRITICAL |

**Escalation overrides** (applied after banding, and recorded as such):

- Any life-threatening medical or trapped-person report → at least `HIGH`.
- Any report with ≥1 mobility-limited person *and* a life-threatening flag →
  `CRITICAL`.
- Report age > 6 h while still `NEW` → raise one band (a report nobody has
  triaged is itself a problem). Note that age therefore acts **twice**: it
  contributes capped points to the score, and past the threshold it also raises
  the band. This is deliberate — an untriaged report is both more urgent and a
  process failure — and both effects appear separately in the breakdown.

## 8.3 Ageing

Priority is not frozen at creation. A scheduled job (`priority:refresh`, every
5 minutes) recomputes the age-dependent terms for all non-terminal incidents. A
band change writes to `status_history` with `source = 'system'`, so the queue
re-sorts itself and the change is visible rather than mysterious.

## 8.4 Explainability payload

`emergencies.priority_breakdown` stores the trace:

```json
{
  "config_version": 3,
  "score": 81,
  "level": "CRITICAL",
  "factors": [
    {"rule": "base_severity",      "detail": "MEDICAL",              "points": 35},
    {"rule": "life_threatening",   "detail": "reporter asserted",    "points": 25},
    {"rule": "affected_count",     "detail": "4 persons (band 2-5)", "points": 5},
    {"rule": "elderly",            "detail": "2 elderly",            "points": 6},
    {"rule": "mobility_limited",   "detail": "1 person",             "points": 5},
    {"rule": "report_age",         "detail": "1.0 h unresolved",     "points": 2},
    {"rule": "multi_hop_arrival",  "detail": "arrived at hop 3",     "points": 3}
  ],
  "escalations": [
    {"rule": "life_threatening_medical_min_high", "applied": false,
     "note": "already above HIGH"}
  ],
  "computed_at": "2026-08-27T04:12:09Z",
  "computed_by": "server"
}
```

The incident detail page renders this as a plain-language list. This is the
single highest-value five minutes of a defense demo.

### Parity is checked, not assumed

The two implementations are pinned by the **same four worked examples asserted in
both suites** — `PriorityEngineTest.php` and `PriorityParityTest.kt`.
`android/core-mesh/priority-parity-check.sh` runs both and reports together.

Without that, a drift would surface as a resident seeing one priority on their
phone and an operator seeing a different one on the dashboard, for the same
emergency — with nothing in either codebase failing.

## 8.5 Worked examples (these are the unit-test fixtures)

**A — Elderly cardiac emergency, 4 affected, arrived at hop 3**
35 + 25 + 5 + 6 + 5 + 2 + 3 = **81 → CRITICAL**

**B — Minor flooding, 3 affected, no vulnerable persons, fresh**
30 + 0 + 5 + 0 + 0 + 0 + 0 = **35 → MODERATE**

**C — Reported road damage, 1 affected, fresh**
15 + 0 + 0 + 0 + 0 + 0 + 0 = **15 → LOW**

**D — House fire, 12 affected incl. 3 children and 2 elderly, 2 h old**
40 + 25 + 15 + 12 + 6 + 0 + 4 = **102 → clamped 100 → CRITICAL**

## 8.6 Human override

An operator may raise priority; only an official may lower it. Both require a
written reason, are stored in `priority_overridden_by` /
`priority_override_reason`, and are written to `audit_logs`. An overridden
incident stops being auto-recomputed by the ageing job and is flagged in the UI
as manually set — otherwise the job would silently undo a human decision.

## 8.7 Configurability

All weights, bands, and escalation rules live in `settings.priority_config` as
versioned JSON. Changing them bumps `config_version`; historical breakdowns keep
the version they were computed under, so past decisions remain readable in the
terms that produced them. This satisfies §9's requirement that the formula be
configurable and clearly documented — without rewriting history.
