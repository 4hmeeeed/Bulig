---
title: Bulig — documentation index
tags: [bulig, index]
status: living
---

# Bulig — documentation index

> **Bulig: An Offline-First Emergency Communication and Disaster Response
> Coordination System for a Selected Barangay in Tacloban City**
>
> *Bulig* (Waray-Waray): "help."

The map of everything written down. Start here if you have just opened the vault
or the repository.

Every document carries a `status` in its frontmatter, and that field is the one
worth trusting:

| Status | Means |
|---|---|
| `specified` | Designed and written down. No code has proven it. |
| `implemented` | Code exists and its automated tests pass. |
| `verified-on-hardware` | Observed working on a real phone or a real server. |

The distinction is not bureaucracy. This project's central claim is about what
happens on real handsets during a disaster, and a design document that has never
met a radio is evidence of intent, not of function.

---

## Start here

| Document | What it is for |
|---|---|
| [Status](STATUS.md) | **What is proven, what is not, and what happens next** |
| [README](../README.md) | What Bulig is, and the repository layout |
| [PREREQUISITES](../PREREQUISITES.txt) | What to install on a bare machine |
| [RUNNING](../RUNNING.md) | How to build, run and test the whole system |
| [Limitations](LIMITATIONS.md) | What this system does **not** do. Read before believing anything else |

## The system

| Document | What it fixes |
|---|---|
| [01 — Architecture](01-architecture.md) | The three-tier shape: phones, mesh, barangay server |
| [02 — Roles and permissions](02-roles-permissions.md) | Five roles, enforced server-side; the UI only hides what the server already refuses |
| [03 — Screens and navigation](03-screens-navigation.md) | Structure, hierarchy and content of every screen — the layer that must be right before pixels |
| [04 — Database design](04-database-erd.md) | ERD and data dictionary for the server's schema |

## The protocol — the part the capstone is actually about

| Document | What it fixes |
|---|---|
| [06 — BLE mesh protocol](06-ble-protocol.md) | How an emergency packet moves phone to phone with no infrastructure |
| [07 — Offline storage and sync](07-offline-sync.md) | The local-first write path, and what happens when signal returns |
| [05 — API contract](05-api-contract.md) | The REST surface the phones and the command center share |
| [08 — Priority engine](08-priority-engine.md) | Deterministic, explainable ranking. No machine learning, on purpose |

## Operating and proving it

| Document | What it fixes |
|---|---|
| [09 — Workflows](09-workflows.md) | The emergency lifecycle, end to end |
| [10 — Testing and evaluation plan](10-testing-plan.md) | Automated tests prove the protocol; phones prove the physics |
| [11 — Device bring-up](11-device-bringup.md) | What actually broke getting this onto real hardware, and why |

## Design

| Document | What it is |
|---|---|
| [Handoff](design/HANDOFF.md) | The mobile design handoff, authoritative for copy |
| [Design reconciliation](design/DESIGN-RECONCILIATION.md) | Where the handoff and the built system agree, disagree, and why |
| [Diagrams brief](design/DIAGRAMS-BRIEF.md) | Brief for the defense diagrams |

---

## Reading paths

**"I am reviewing this project."**
[README](../README.md) → [01 — Architecture](01-architecture.md) →
[06 — BLE mesh protocol](06-ble-protocol.md) → [Limitations](LIMITATIONS.md).
The last one is not optional; it is where the claims are bounded.

**"I have to run it."**
[PREREQUISITES](../PREREQUISITES.txt) → [RUNNING](../RUNNING.md) →
[11 — Device bring-up](11-device-bringup.md) when something fails.

**"I want to know whether the mesh really works."**
[06 — BLE mesh protocol](06-ble-protocol.md) for the design,
[10 — Testing plan](10-testing-plan.md) §field tests for how it is proven, and
[Limitations](LIMITATIONS.md) §1–6 for why "works" is a narrower word here than
it sounds.
