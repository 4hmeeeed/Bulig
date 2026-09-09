# Bulig — working notes for Claude Code

> **Bulig: An Offline-First Emergency Communication and Disaster Response
> Coordination System for a Selected Barangay in Tacloban City**
>
> *Bulig* (Waray-Waray): "help."

A 4th-year IT capstone. Residents file emergency reports on phones with no
signal; reports hop **phone to phone over Bluetooth LE** until some device with
connectivity uploads them to a barangay command center.

**The research claim, in one sentence:** a report can be authored on a phone
that never regains signal, carried by a stranger's phone, and delivered by a
third device that never authored it. Everything else in this repository exists
to support or bound that claim.

Read `docs/STATUS.md` first — it says what is proven and what is not.

---

## Constraints that must not be redesigned away

These are the project, not implementation details. If a change would violate
one, say so rather than quietly working around it.

- **Offline-first.** Filing a report never blocks on the network. A phone that
  has never once reached the server must still capture, store and relay.
- **BLE store-and-forward mesh.** Not Wi-Fi Direct, not a server-brokered
  queue. Devices carry other people's reports and hand them on.
- **No AI/ML anywhere in the core.** Priority is a deterministic rule engine
  producing an explainable trace. When a panel asks "why is this CRITICAL?",
  the answer is a list of rules that fired, not a weight vector.
- **Data minimisation.** The mesh carries the minimum that makes a report
  actionable. Nothing broadcasts a device name or a resident's identity.

## Honesty rules

Several defects in this project were treated as serious because they broke one
of these. They are the spine of the work.

- **Never claim a delivery that has not happened.** Green, and the word
  "delivered", are reserved for a server acknowledgement. A report sitting on a
  phone says "Saved on your phone" in grey. `DeliveryHonestyTest` enforces this.
- **Never overstate what the hardware can do.** A handset that cannot BLE
  advertise can never be found, so it can never receive a report to carry — and
  the app says so rather than looking healthy. See `MeshRadioStatus` and
  `HomeUiState.meshStripText`.
- **Never invent field facts.** Anything about the barangay, the population, or
  real-world radio behaviour that has not been measured is marked
  `TO BE VALIDATED` in the docs. Do not replace such a marker with a plausible
  number.
- **Logs carry counts and outcomes, never identities.** `adb logcat -s BuligSync
  BuligMesh` is readable by anyone holding the phone. Peers appear as a
  four-hex-character tag, never a Bluetooth address; sync logs report how many
  packets were accepted or rejected and why, never who filed what.
- **Distinguish written from proven.** Every document in `docs/` carries
  `status:` frontmatter — `specified`, `implemented`, or
  `verified-on-hardware`. That field is the one to trust. Passing tests are not
  the same as working software; `docs/11-device-bringup.md` is the evidence.

## Layout

```
docs/        design documents — docs/00-index.md is the map, start there
backend/     Laravel 12 REST API + Livewire barangay command center
android/
  core-mesh/ relay engine — pure JVM, no Android SDK needed
  data/      storage, sync, delivery state — pure JVM
  app/       Compose UI, BLE service, Room/SQLCipher
```

`:core-mesh` and `:data` are deliberately pure Kotlin so the logic that decides
what a resident is told is testable without a device or an Android SDK. **If a
decision lives in a `@Composable`, it is in the wrong place.** Nothing may put
the Android Gradle Plugin on the shared root classpath — see the comment in
`android/build.gradle.kts`, which explains an arrangement that took two failed
attempts to get right.

## Commands

```bash
# Kotlin — needs JDK 21. Android Studio's bundled JBR is 25 and Gradle 8.10.2
# rejects it outright with "* What went wrong: 25.0.3". Set JAVA_HOME first.
cd android && ./gradlew.bat :core-mesh:test :data:test
./gradlew.bat :app:assembleDebug

# Backend
cd backend && php artisan test
php artisan serve --host=0.0.0.0 --port=8000   # 0.0.0.0, or no phone can reach it

# On device
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -s BuligSync BuligMesh
```

A debug build reads the server address from `bulig.baseUrl` in
`android/local.properties` (git-ignored) and exposes it as
`BuildConfig.BASE_URL`. Absent, it falls back to the emulator's `10.0.2.2`,
which is wrong on every physical phone. Do not put a LAN address in
`app/src/main/res/xml/network_security_config.xml` — that file is the release
posture and is deliberately HTTPS-only.

Machine-specific values — JDK path, this laptop's LAN IP, git remote details —
are in `CLAUDE.local.md`, which is git-ignored because they are facts about
whoever is testing rather than about the app.

## Working agreements

- Commit messages explain **why**, including approaches that were tried and did
  not work. `docs/11-device-bringup.md` records a Gradle version pin applied on
  a theory that turned out to be wrong; the tidier story would have been less
  useful to the next person.
- Push to `origin` when a change is good. The branch is
  `claude/bulig-emergency-system-9qzwst`.
- The docs are also an Obsidian vault rooted at the repository. Links are
  ordinary markdown, not wikilinks, so they work both in the graph and on
  GitHub. Keep it that way.
