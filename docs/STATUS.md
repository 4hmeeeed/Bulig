---
title: "Project status"
tags: [bulig, status, living]
status: living
---

# Project status

**As of 2026-09-09, commit `1cf74bd`+, branch
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

## The mesh works. Demonstrated end to end on 2026-09-09.

**A report was delivered by a phone that did not write it.** Two handsets, an
Infinix X6731 and a Samsung SM-A256E, with the backend server stopped:

1. The server was shut down, so nothing could be uploaded by anyone.
2. A Fire report was filed on the Infinix. Its confirmation read *"Saved on
   your phone — your report has not reached the barangay yet"*.
3. The two phones met over BLE. The Infinix logged
   `sending one packet as 1 frames`; the Samsung's carried count went 1 to 2.
4. The Infinix's Wi-Fi was switched off, so it could never deliver anything.
5. The server was restarted. The Samsung synced:
   `attempted=2 accepted=2 duplicate=0 rejected=0`.

The server recorded packet 12 as `origin_device = 6` (the Infinix),
`current_device = 7` (the Samsung), `hop_count = 1`,
`hmac_valid = true`, `status = ACCEPTED`, and raised emergency
`BLG-2026-0009`.

That is the claim: authored on a phone with no signal, carried by a stranger's
phone, delivered by a device that never wrote it.

Six defects had to be fixed to get there, all of them invisible to the test
suites and all recorded in [11 — Device bring-up](11-device-bringup.md). The
last was the decisive one: the mesh read its outbound packet list on the main
thread, Room refuses main-thread queries, and the exception was swallowed into
an empty list — so every encounter completed perfectly and carried nothing.

### Still unproven

- More than two phones, and more than one hop.
- Any real distance, obstruction, or movement. All of the above happened with
  two handsets side by side on a desk.
- Battery cost over a sustained period.

## Tests

| Suite | Count | Command |
|---|---|---|
| `:core-mesh` | 164 | `./gradlew.bat :core-mesh:test` |
| `:data` | 337 | `./gradlew.bat :data:test` |
| backend | 66 (190 assertions) | `php artisan test` |

**568 total** (`:data` gained a millisecond round-trip test), all passing. `:app` has no automated tests and
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

### 2. Widen the mesh evidence

The single-hop case is proven. What is not:

- **Three or more phones, and two or more hops.** Every relay rule in
  `:core-mesh` — TTL, dedup, forwarding policy — is only exercised by tests.
- **Distance, walls and movement.** The demonstration happened with two
  handsets side by side on a desk.
- **Battery cost.** `docs/LIMITATIONS.md` §5 calls this a real constraint and
  no measurement exists.

### 3. Known rough edges, none blocking

- The advertisement payload is built once in `startAdvertising` and never
  rebuilt, so `hasInternet` and `pendingCount` go stale. Peers use
  `hasInternet` to prioritise, so this misinforms the mesh.
- Scanning runs at `SCAN_MODE_LOW_LATENCY` with no report delay — several
  callbacks a second per peer, which is more radio and CPU than a disaster app
  should spend.
- When Bluetooth is off the mesh strip still says "No Bulig phones nearby"
  rather than saying the radio is off. It reports having looked when it never
  looked.

## Deferred, with reasons

| Item | Why it is waiting |
|---|---|
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
