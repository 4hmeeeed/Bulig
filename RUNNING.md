# Running and testing Bulig

Everything below assumes a fresh machine. Work through it in order — each stage
either passes or tells you exactly what is wrong, and a later stage will not
make sense if an earlier one failed.

---

## 0 · Get the code

```bash
git clone https://github.com/4hmeeeed/Bulig.git
cd Bulig
git checkout claude/bulig-emergency-system-9qzwst
```

If you already have the repo, `git pull` on that branch.

---

## 1 · Prove the tested modules still pass (2 minutes)

Do this **first**, before Android Studio. It needs only a JDK, and it tells you
whether the 432 Kotlin tests still pass on your machine rather than mine.

```bash
cd android
./gradlew :core-mesh:test :data:test
```

**Expect:** `BUILD SUCCESSFUL`.

If this fails, nothing else is worth trying — the failure is in the environment
(wrong JDK, no network for Maven Central), not in the code.

- Needs **JDK 17 or newer**. `java -version` to check.
- No Android SDK required for this step. That is the point of the module split.

HTML reports land in `core-mesh/build/reports/tests/test/index.html` and
`data/build/reports/tests/test/index.html` — open them for the per-test list, which
is worth a screenshot for your defense.

---

## 2 · Run the backend (10 minutes)

```bash
cd backend
cp .env.example .env          # only if .env is missing
composer install
php artisan key:generate
touch database/database.sqlite
php artisan migrate --seed
php artisan test              # expect 66 passed
php artisan serve
```

**Expect:** `66 passed (190 assertions)`, then a server on
`http://127.0.0.1:8000`.

The command center is at that address. `DemoDataSeeder` creates synthetic
incidents and accounts — **synthetic only**, per the test-data policy in
`docs/10-testing-plan.md` §10.8. Login credentials are printed by the seeder.

Needs **PHP 8.2+** and Composer. SQLite is the default so you do not need MySQL
to try it; switch `DB_CONNECTION` to `mysql` when you set up the real pilot.

---

## 3 · Build the Android app — the real unknown

**This is the first time `:app` will ever meet a compiler.** 24 files, ~5,300
lines, written against the documentation and never verified. Assume the first
build fails.

1. Open **Android Studio** → `Open` → select the `android/` folder (not the repo
   root).
2. Let it sync. It will download the Android SDK pieces, AGP, Compose and Room.
   This is the step that could not happen in the development container.
3. `Build` → `Make Project`.

### When it fails

`android/BUILDING.md` lists **13 predicted failures** with fixes. The five most
likely, in order:

1. **Material icon names** — `components/EmergencyIcons.kt`. `material-icons-extended`
   does not carry every Material Symbol. Every mapping is in one function.
2. **KSP version** — `2.0.21-1.0.28` must pair with Kotlin 2.0.21 exactly.
3. **`onCharacteristicRead` overloads** — the 4-arg form is API 33+.
4. **SQLCipher import path** — `net.sqlcipher.database.SupportFactory`.
5. **`security-crypto` is alpha-only** — `1.1.0-alpha06`, not the `1.0.0` line.

**Send me the errors.** Paste the compiler output and I will work through them
with you. Do not spend an evening fighting them alone — most will be one-line
fixes, and I wrote the code that caused them.

---

## 4 · Run it on one phone

Once it builds:

1. Set `Bulig.BASE_URL` in `app/src/main/kotlin/ph/bulig/app/Bulig.kt`:
   - **Emulator** → leave it as `http://10.0.2.2:8000` (the emulator's view of
     your machine).
   - **Real phone** → your machine's LAN address, e.g. `http://192.168.1.14:8000`,
     **and** add that address to `app/src/main/res/xml/network_security_config.xml`
     or the request is blocked as cleartext.
2. Run.

### What to check, in order

| Check | What proves it |
|---|---|
| Permission screen appears | Rationale text, three rows, "Not now" works |
| File a report | Type → details → location → review → confirmation |
| Confirmation is **grey**, not green | Says "Saved on your phone" |
| My reports lists it | Chip reads SAVED ON PHONE |
| **Kill the app and reopen** | The report is still there — this is Room working |
| Turn on Wi-Fi, wait ~15 min or file another | Chip turns green, code appears |
| Command center shows it | Incident appears in the queue with a priority |

That last pair is the full round trip: **phone → server → operator screen.**

---

## 5 · Two phones — the actual research claim

This is the one that matters, and the one nothing so far has proven.

1. Install on **two** phones. Different manufacturers if you can — that is where
   compatibility problems appear.
2. Both: Bluetooth **on**, mobile data and Wi-Fi **off** (airplane mode with
   Bluetooth re-enabled works).
3. File a report on phone A.
4. Bring them within a few metres.
5. Watch phone A's Mesh Status. `BULIG PHONES NEARBY` should become 1.
6. Give it a minute. Phone A's report chip should become **CARRIED BY 1 PHONE**.
7. Now turn Wi-Fi on for **phone B only**.
8. Phone A's report should eventually read **DELIVERED** — uploaded by a phone
   that never wrote it.

**If step 8 works, your capstone's central claim is demonstrated.** Record it.

### If it does not work

That is data, not failure. Note *where* it stopped:

- Never sees each other → advertising or scanning; check both granted permissions
- Sees but never connects → GATT connection limits or the service UUID
- Connects but nothing transfers → the GATT server or the characteristics
- Transfers but never delivers → sync, not mesh

Tell me which, and we debug from there. `docs/LIMITATIONS.md` §2–§4 already say
BLE range is short and Android background execution is hostile — an unfavourable
result reported honestly is a legitimate finding, and this project was designed
so it can be one.

---

## 6 · The field test proper

Only once step 5 works. `docs/10-testing-plan.md` §10.4 has the full protocol:
3–5 devices, 10 runs per configuration, distance × obstruction × chain length ×
battery × screen state. Record every run **including the failures** — §10.4 says
it in the document because a capstone reporting 100% success has not tested
honestly.

`EncounterRecorder` produces §24 metric 2 (discovery time) on the device;
`GET /api/v1/metrics/evaluation` produces metrics 3–8 from the server's own
tables. Metrics 1, 9 and 10 come from a stopwatch, browser instrumentation and
Battery Historian respectively — §10.5 says which is which.

---

## Quick reference

```bash
# Kotlin tests, no Android SDK needed
cd android && ./gradlew :core-mesh:test :data:test

# Backend tests
cd backend && php artisan test

# Backend server
cd backend && php artisan serve

# Everything, one line
cd android && ./gradlew :core-mesh:test :data:test && cd ../backend && php artisan test
```

**498 tests should pass**: core-mesh 164, data 268, backend 66.
