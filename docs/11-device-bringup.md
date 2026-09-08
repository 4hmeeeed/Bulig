---
title: 11 — Device bring-up
tags: [bulig, android, build, field-notes]
status: verified-on-hardware
---

# 11 — Device bring-up

What actually happened taking Bulig from "the tests pass" to "it runs on a
phone and talks to the server", and why each thing broke.

This is written down for three reasons. It is the honest record of a step the
[testing plan](10-testing-plan.md) treats as a single line. It stops the next
person re-deriving a day of diagnosis. And it is the evidence for a claim this
project makes repeatedly — that passing tests and working software are not the
same thing, and that the gap between them is where the real defects live.

Every module's automated tests passed **before** any of this. The `:app` module
had never been compiled once.

## Summary

Nine defects, in four groups. None were found by the test suites, because none
of them live in code the test suites can reach: they are in the build
configuration, the packaging, the native layer, and the network's response to
a real client.

| # | Where | Symptom | Cause |
|---|---|---|---|
| 1 | Gradle plugins | `plugin is already on the classpath` | Kotlin plugin markers versioned in two places |
| 2 | Gradle plugins | `NoClassDefFoundError: BaseVariant` | Kotlin plugin resolved into a classloader that could not see AGP |
| 3 | Gradle config | `AndroidX dependencies … property is not enabled` | `android.useAndroidX` never set |
| 4 | Module boundaries | `Cannot access 'Json' / 'OkHttpClient'` | `:data` exposed them in its API but declared them `implementation` |
| 5 | Native library | `Unresolved reference 'sqlcipher'` | Import from the superseded SQLCipher artifact |
| 6 | Packaging | `not enough space` | Four ABIs of unstripped native code in a debug APK |
| 7 | Packaging | `INSTALL_PARSE_FAILED_NO_CERTIFICATES` | APK signed with v2 only |
| 8 | Runtime | `UnsatisfiedLinkError: nativeOpen` | SQLCipher's native library never loaded |
| 9 | Protocol | Phone stopped syncing permanently | HTTP 429 classified as revocation |

---

## Group 1 — The build system (defects 1–3)

The first Android Studio sync failed with `the plugin is already on the
classpath` for `org.jetbrains.kotlin.android`. All of Kotlin's plugin markers —
`jvm`, `android`, `plugin.compose` — resolve to the same `kotlin-gradle-plugin`
artifact, and it was versioned in the root build file *and* in `:app`.

Moving the version to the root cleared that and produced a different failure:
`NoClassDefFoundError` on `com.android.build.gradle.api.BaseVariant`. The Kotlin
Android plugin instantiates `KotlinAndroidTarget`, which resolves that class out
of the Android Gradle Plugin — and AGP was declared only in `app/build.gradle.kts`,
a child classloader the root one cannot see.

The two constraints are in tension. Declaring AGP at the root satisfies the
classloader but forces every build to resolve AGP from Google's Maven, which
breaks `:core-mesh` and `:data` on a machine that cannot reach it — the property
[06 §6.9](06-ble-protocol.md) depends on. The arrangement that satisfies both is
to put **nothing** on the shared parent classpath and let each module version
what it uses, so `:app` gets AGP and `kotlin("android")` together and the
pure-Kotlin modules never mention AGP at all.

> **An honest note.** Between those two, the Gradle wrapper was pinned from
> 8.14.3 down to 8.10.2 on the theory that `BaseVariant` was an AGP/Gradle
> version mismatch. It was not — the classloader arrangement was the cause. The
> pin was kept because AGP 8.7.3 is validated against 8.10.2 anyway, but it did
> not fix anything, and recording that is more useful than a tidy narrative.

Defect 3 was one missing line: every dependency in `:app` is an `androidx.*`
artifact, and AGP refuses to build against them unless `android.useAndroidX` is
set explicitly rather than inferred.

## Group 2 — Module boundaries and a renamed artifact (defects 4–5)

The first successful *configuration* produced the first real compile errors.

`HttpSyncApi` and `AssignmentActions` in `:data` expose `Json` and
`OkHttpClient` directly in their public signatures, but `:data` declared both as
`implementation`, which Gradle does not pass through to a consumer. `:app`
constructs those types itself, so it could not see them. Changing the two
declarations to `api` fixed it — and the underlying lesson is that
`implementation` versus `api` is a statement about your public surface, and the
compiler only checks it once someone downstream exists.

`ReportDatabase` imported `net.sqlcipher.database.SupportFactory` — the class
name from the legacy `net.zetetic:android-database-sqlcipher`. The build depends
on the modern `net.zetetic:sqlcipher-android`, which repackaged everything under
`net.zetetic.database.sqlcipher` and renamed the Room integration class to
`SupportOpenHelperFactory`. Confirmed against the published 4.6.1 AAR rather
than guessed: same single-`ByteArray` constructor, so a drop-in replacement.

## Group 3 — Getting the APK onto a phone (defects 6–7)

`adb install` failed with `Requested internal only, but not enough space`. The
build log explained it: `Unable to strip the following libraries … libsqlcipher.so`.
SQLCipher ships native code for four ABIs, there is no NDK on the build machine
to strip it, and three of those four the phone can never execute. Debug builds
now filter to `arm64-v8a`; release builds stay universal.

Then `INSTALL_PARSE_FAILED_NO_CERTIFICATES: … APK Signature Scheme v2: SHA-256
digest of contents did not verify` — while `apksigner verify` on the same file
reported the signature as valid. The APK was fine; the device's verifier was
failing on it, and `apksigner --verbose` showed why it had no second chance:
`v1: false, v2: true, v3: false`. AGP omits v1 (JAR) signing when `minSdk >= 24`
and does not enable v3 for debug, so the handset had exactly one scheme to
verify and no fallback. Enabling v1, v2 and v3 fixed it; v3 is what the device
accepted.

Two environmental problems wore the same disguise and cost the most time. A
failing USB cable produced truncated transfers that surface as *the same*
digest error — the giveaway was `adb` losing the device three times in a
session. Switching to wireless debugging removed it entirely. And a debug APK
this size needs real free space; `-r` needs room for both copies at once.

> The emulator was never a route out of this. Its Vulkan check fails on this
> hardware (`1.2.133`, minimum `1.3.0`), so it falls back to SwiftShader
> software rendering and does not finish booting — and an emulator has no
> Bluetooth radio, so it could never have run the mesh test anyway.

## Group 4 — Runtime and protocol (defects 8–9)

The app installed, launched, and died on its first database read:

```
java.lang.UnsatisfiedLinkError: No implementation found for
net.zetetic.database.sqlcipher.SQLiteConnection.nativeOpen(...)
- is the library loaded, e.g. System.loadLibrary?
```

The second half of the artifact rename in defect 5. The legacy library loaded
its own native code inside `SQLiteDatabase.loadLibs(context)`; the modern one
expects an explicit `System.loadLibrary("sqlcipher")`. Swapping the class names
left every Java class resolving happily with nothing behind it — which is why
the failure surfaced at the first query and named a JNI symbol rather than the
cause.

Then both phones stopped syncing entirely, and nothing in logcat said why,
because the app had **no logging at all**. Adding it to the sync path answered
the question in one run:

```
device refused by server: {"message":"Too Many Attempts.",
"exception":"Illuminate\Http\Exceptions\ThrottleRequestsException"}; not retrying
```

`devices/register` allows three attempts an hour — deliberately, since
re-registering rotates the signing key. A phone that has never registered asks
on every sync run and reaches that ceiling in minutes. `DeviceRegistrar`
classified anything that was not 403 or 5xx as a permanent rejection, which
`RegistrationManager` reports as `Refused`, which `SyncWorker` correctly treats
as terminal — so a throttle that clears in an hour ended the device's life, and
reports sat undelivered on a phone with a working network. `HttpSyncApi` had
always classified 429 correctly for the *upload* path; the registration path
was a second, separate mapping that simply did not know about it.

---

## What this changed about the code

Beyond the nine fixes:

- **The sync path logs** what it tried and what came back, under `BuligSync`.
  Counts, outcomes and the server's rejection reasons — never who filed what.
- **The radio path logs** too, under `BuligMesh`, added *before* the two-phone
  test rather than after it. On one phone a mesh that silently does nothing is
  indistinguishable from a mesh that found no peer, and defect 9 showed exactly
  how expensive that ambiguity is.
- **The server address left the source tree.** It is a fact about whoever is
  testing, not about the app; it now comes from `local.properties`. See
  [RUNNING](../RUNNING.md) §4.

## Environment notes

- **JDK.** Gradle 8.10.2 rejects Android Studio's bundled JBR, which is now
  JDK 25 (`* What went wrong: 25.0.3`). Build with a JDK 21 —
  `JAVA_HOME=~/.jdks/jbr-21.0.11` works.
- **Composer.** `composer.lock` must be generated against the PHP the project
  actually targets. A lock file produced on 8.4 refuses to install on 8.2 even
  when `composer.json` allows it; `config.platform.php` pins the resolver.

## Where this stands

Verified on hardware: the app builds, installs, launches, opens the encrypted
database, registers a device, and uploads a report the server accepts and shows
in the command center.

Not yet verified: **the mesh itself**. `BuligMeshService` is complete and now
instrumented, but it has never been executed against a real radio with a second
phone present. Until it has, [Limitations](LIMITATIONS.md) §1–6 describe
intent rather than observation, and this document says so plainly.
