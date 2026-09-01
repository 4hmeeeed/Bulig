# Building the Android app

## What is verified, and what is not

| Module | Built and tested in CI / this repo? |
|---|---|
| `:core-mesh` | **Yes** — 69 tests |
| `:data` | **Yes** — 60 tests |
| `:app` | **No.** Authored, never compiled. Slice 1 only. |

`:app` needs the Android Gradle Plugin, which resolves from Google's Maven. Any
machine without the Android SDK — including the environment this was written in
— cannot build it. `settings.gradle.kts` therefore includes `:app` **only** when
an SDK is present, so `gradle test` still works everywhere else.

That split is deliberate. Everything that decides what a resident is told, how
packets dedupe, what syncs first, and what colour a delivery chip is has already
been tested off-device. `:app` renders those decisions; it does not make them.

**Expect this slice to need fixes on first build.** It has never seen a
compiler. The point of shipping one screen rather than twelve is that you find
the version and toolchain problems once, not thirty times.

---

## First build

```bash
# 1. Open android/ in Android Studio (Ladybug 2024.2.1 or newer).
#    It writes local.properties with your SDK path, which is what makes
#    settings.gradle.kts include :app.

# 2. Or from the CLI, if the SDK is already installed:
export ANDROID_HOME=$HOME/Android/Sdk
cd android
./gradlew :app:assembleDebug
```

Confirm the pure-Kotlin modules still pass, which they should regardless:

```bash
cd android && gradle test
```

---

## Where this is most likely to break

Ordered by how likely I think each is. None of these are guesses about *your*
setup — they are the places where an uncompiled Compose module usually fails.

### 1. Compose BOM ↔ Kotlin version

`app/build.gradle.kts` pins Compose BOM `2024.12.01` against Kotlin `2.0.21` and
AGP `8.7.3`. Kotlin 2.0 moved the Compose compiler into the
`org.jetbrains.kotlin.plugin.compose` plugin, which is applied — but if Android
Studio ships a newer Kotlin, the plugin version must move with it.

**If it fails:** match `kotlin("android")` and `kotlin("plugin.compose")` to the
Kotlin version the IDE reports, and bump the BOM to the current stable.

### 2. Missing Material icons

`Icons.Filled.Sos`, `.Hub`, `.SignalCellularOff`, `.HourglassTop`,
`.ChangeHistory`, `.Square`, `.Circle`, `.TaskAlt`, `.Badge`, `.DirectionsWalk`
all come from `material-icons-extended`, which is declared. Names occasionally
differ between versions.

**If it fails:** the icon names are isolated in exactly two places —
`iconFor()` in `ConnectivityBanner.kt` and `deliveryIcon()` in `Chips.kt`. Fix
them there; nothing else references an icon directly.

**Do not substitute a shape that loses the distinction.** The four priority
icons are deliberately different silhouettes — octagon, triangle, square, circle
— because barangay offices print rosters on mono laser printers and a colour-blind
operator has to read them at a glance. Four colours of the same dot would fail
both.

### 3. `enableEdgeToEdge()`

Needs `androidx.activity:activity-compose:1.9.x`, which is declared. On older
Activity versions the call does not exist.

**If it fails:** delete the call. It is cosmetic.

### 4. Adaptive launcher icon

`ic_launcher_foreground.xml` is a placeholder vector, and there is no
`mipmap-*/ic_launcher.png` fallback for pre-API-26 densities. minSdk is 26 so the
adaptive icon suffices, but some tooling still wants the raster.

**If it fails:** right-click `res` → New → Image Asset in Android Studio and let
it generate the set.

### 5. Gradle JDK

The modules emit Java 17 bytecode. Android Studio's bundled JDK is 17 or 21;
either is fine. A JDK 11 configured under Settings → Build Tools → Gradle is not.

---

## What slice 1 contains

- `theme/Tokens.kt` — every colour, size and type value from the design handoff,
  each carrying its source `oklch()` in a comment. **Nothing outside this file
  may declare a colour.**
- `theme/Theme.kt` — Material 3 wiring. No dynamic colour (Material You would
  recolour delivery chips from the wallpaper, and those colours carry meaning),
  and no dark theme yet (which greens still mean "confirmed" in the dark is the
  designer's call, not a guess made here).
- `components/ConnectivityBanner.kt` — the six-state persistent banner.
- `components/Chips.kt` — priority and delivery chips.
- `screens/HomeScreen.kt` — artboard 01.
- `MainActivity.kt` — renders Home with state from the real, tested factory.

## What it does not contain

The type picker, the report flow, location confirm, review, submitted, my
reports, report detail, mesh status, and all three responder screens. Also Room,
SQLCipher, Retrofit, WorkManager and the BLE service.

Those come next, once this compiles.

---

## Once it builds

Tell me it compiled and what you had to change. I will fold the fixes into the
remaining slices so the same problems are not repeated eleven more times.

## BLE slice — what has and has not seen a compiler

| File | Module | Compiled | Tested |
|---|---|---|---|
| `GattContract.kt`, `BleSession.kt`, `CharacteristicCodecs.kt` | `:core-mesh` | yes | 120 tests |
| `BuligMeshService.kt`, `MeshRadioStatus.kt` | `:app` | **no** | none |

`:app` cannot be compiled in the development container — Google's Android Maven
mirror is unreachable there, which is why `settings.gradle.kts` only includes
`:app` when an SDK is present. Everything in `:app` is written against the
Android documentation and has never been checked by a compiler.

Likely first-build failures in the BLE files, in the order they will appear:

1. **`onCharacteristicRead` signature.** The 4-argument overload with a
   `ByteArray value` is API 33+. On a `compileSdk` below 33 only the deprecated
   3-argument form exists, and the override will not resolve. Fix: keep the
   4-arg override and add the deprecated 3-arg one delegating to it with
   `characteristic.value`.
2. **`ADVERTISE_FAILED_FEATURE_UNSUPPORTED`** is a constant on `AdvertiseCallback`;
   referencing it unqualified inside the anonymous object should work, but if it
   does not, qualify it as `AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED`.
3. **`startForeground` on API 34+** requires a `foregroundServiceType` argument
   and a matching `android:foregroundServiceType="connectedDevice"` in the
   manifest, plus the `FOREGROUND_SERVICE_CONNECTED_DEVICE` permission.
4. **Missing permissions annotations.** Lint will flag every Bluetooth call
   without `@RequiresPermission`. The `SecurityException` catches make these
   safe at runtime; add `@SuppressLint("MissingPermission")` where lint insists.
5. **`R.drawable.ic_launcher_foreground`** must exist. If the launcher icon was
   generated differently, point the notification at whatever icon does exist.

### Deliberately still unwired

- `heldPackets` is always empty: the repository is not connected to the service
  yet, so the device advertises `pending_count = 0` and offers nothing.
- The **GATT server role** is not implemented. `BuligMeshService` currently only
  advertises and acts as a central. Until the server side exists, two Bulig
  phones can find each other but neither can answer a read — so nothing moves.
  This is the single largest remaining gap in the BLE work.
- `ACK` notifications are not subscribed to, so `BleEvent.PacketAcked` is never
  produced on a device. `BleSession` handles it and is tested for it; nothing
  yet feeds it.
