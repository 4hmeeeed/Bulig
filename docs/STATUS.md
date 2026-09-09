---
title: "Project status"
tags: [bulig, status, living]
status: living
---

# Project status

**As of 2026-09-08, commit `2c58577`, branch
`claude/bulig-emergency-system-9qzwst`.**

A living document. It exists because passing tests and working software are not
the same thing, and this project has now been on the wrong side of that gap
several times. Update it when the answers below change.

---

## Proven on real hardware

Observed on a physical handset (OPPO, ColorOS, Android 16), not inferred from
tests:

- The Android app builds, installs, and launches.
- It opens the encrypted SQLCipher database and reads from it.
- It registers a device with the server and receives a signing key.
- It uploads a report the server accepts, and that report appears in the
  barangay command center with a code and a priority.
- The backend serves the command center and the API over the LAN.

## Built, but never executed

**The BLE mesh.** This is the capstone's actual claim and it remains unproven.

`app/src/main/kotlin/ph/bulig/app/ble/BuligMeshService.kt` is ~900 lines of real
`BluetoothLeAdvertiser`, `BluetoothLeScanner` and GATT code, backed by four
`:core-mesh` BLE classes. There are no TODOs or stubs. It is wired into
`BuligViewModel`, bound from `MainActivity`, and every permission is declared.
Its *logic* — framing, chunking, dedup, TTL, forwarding policy, bloom digest —
is unit-tested in pure Kotlin.

It has never run against a real radio with a second phone present.

Until it has, `docs/06-ble-protocol.md` is `specified`, and
`docs/LIMITATIONS.md` §1–6 describe intent rather than observation.

## Tests

| Suite | Count | Command |
|---|---|---|
| `:core-mesh` | 164 | `./gradlew.bat :core-mesh:test` |
| `:data` | 337 | `./gradlew.bat :data:test` |
| backend | 66 (190 assertions) | `php artisan test` |

**567 total**, all passing at this commit. `:app` has no automated tests and
cannot meaningfully have any here; what is claimed for it is that it has been
built and exercised by hand.

---

## Next actions, in order

### 1. The single-phone checklist — never yet run

Needs one handset. Backend on `--host=0.0.0.0` first, and confirm the login page
loads *on the phone* before installing anything. Then, with
`adb logcat -s BuligSync BuligMesh` running:

1. **Offline capture.** Airplane mode, file a report. It must save, and the
   confirmation must be grey — *"Saved on your phone"*, never green. A delivery
   claim made while offline is the worst bug this app could ship.
2. **Persistence.** Force-close, reopen, still offline. The report survives.
   This is the first real proof of the encrypted database across process death.
3. **Sync round trip.** Wi-Fi on. The chip turns green with a code, and the
   incident appears in the command center queue
   (`operator@bulig.test` / `password`).
4. **Mesh smoke test.** Open Mesh Status from the strip on Home. `BuligMesh`
   should log the service starting, the GATT server listening, advertising, and
   scanning. **Whether this handset can advertise is the single most valuable
   output of the session** — if it logs `advertising unsupported`, this phone
   can never receive a relayed report and the two-phone test needs a different
   device.
5. **Responder path.** Sign in as `responder1@bulig.test` / `password` from the
   line at the foot of Home, confirm the assignment queue loads, open an
   assignment, sign out, land back as a resident.

Full checklist with rationale: `RUNNING.md` §4.

### 2. The two-phone mesh test — the research claim

Needs a second handset. Both phones on Bluetooth only, no Wi-Fi or mobile data.
File a report on phone A; bring the phones together; confirm phone B is carrying
it; then enable Wi-Fi on **B only**. Phone A's report must reach the command
center, uploaded by a device that never authored it.

If it fails, the layered breakdown in `RUNNING.md` §5 isolates which stage broke
— discovery, connection, transfer, or sync.

## Deferred, with reasons

| Item | Why it is waiting |
|---|---|
| Two-phone mesh test | Needs a second handset present |
| UI polish | Described as "a bit off"; correctness first |
| Waray-Waray copy review | Needs a native speaker — see `docs/LIMITATIONS.md` §14 |
| Keystore key lifetime | Backup, factory reset and migration paths untested — `docs/LIMITATIONS.md` §9a |

## Known environment traps

Each of these cost real time. Details in `docs/11-device-bringup.md`.

- **JDK 21, not Android Studio's bundled JBR** — that is 25, and Gradle 8.10.2
  refuses it with the unhelpful message `* What went wrong: 25.0.3`.
- **`php artisan serve` must bind `--host=0.0.0.0`**, or no phone can reach it.
- **USB proved unreliable.** A dropping cable truncates `adb install` and
  reports it as an APK *signature* error, which sends you hunting in the wrong
  place. Wireless debugging avoids it entirely.
- **`devices/register` is throttled to 3 attempts per hour per IP.** Clear it
  with `php artisan cache:clear` when testing registration repeatedly.
- **The emulator is not usable on the current machine** — its Vulkan check fails
  on integrated AMD graphics, so it falls back to software rendering and never
  finishes booting. It has no Bluetooth radio either, so it could never run the
  mesh test.

---

**Related:** [Index](00-index.md) · [11 — Device bring-up](11-device-bringup.md) · [Limitations](LIMITATIONS.md) · [10 — Testing plan](10-testing-plan.md)
