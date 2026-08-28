# Bulig Android

## Modules

| Module | Build here? | Contents |
|---|---|---|
| `:core-mesh` | **yes** | Store-and-forward relay engine — dedup, TTL, hop count, forwarding policy, Bloom digest, chunk framing, packet signing. **Pure Kotlin/JVM, zero Android imports.** |
| `:data` | **yes** | Local-first write path, delivery state machine, sync batching and backoff, wire DTOs. **Pure Kotlin/JVM.** Room, Retrofit and WorkManager bindings are thin adapters added with `:app`. |
| `:app` | not yet | Compose UI, BLE foreground service |

`:core-mesh` deliberately has no Android dependency. Bluetooth cannot be
emulated, but relay *logic* does not need a radio: `MeshTransport` is an
interface that `VirtualMesh` implements in tests and the BLE service will
implement on-device. That is what makes proposal TESTS 3, 4 and 5 run as
ordinary JVM unit tests in milliseconds.

```bash
cd android
gradle test                 # both modules
gradle :core-mesh:test      # relay engine
gradle :data:test           # persistence, sync, delivery state
```

### Cross-language wire contract

`:data` emits the exact sync request the Android client would send. A script
feeds it to a **running Laravel server**, so the real validator has the final
say rather than two codebases being assumed to agree:

```bash
cd backend && php artisan serve --port=8401 &
cd android && gradle :data:test && ./data/contract-check.sh
```

Target: **minSdk 26**, Android 12+ permission model
(`BLUETOOTH_SCAN` / `ADVERTISE` / `CONNECT` with `neverForLocation`) as the
primary path, legacy location permission as fallback.
