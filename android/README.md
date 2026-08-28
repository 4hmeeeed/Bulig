# Bulig Android

## Modules

| Module | Build here? | Contents |
|---|---|---|
| `:core-mesh` | **yes** | Store-and-forward relay engine — dedup, TTL, hop count, forwarding policy, Bloom digest, chunk framing, packet signing. **Pure Kotlin/JVM, zero Android imports.** |
| `:data` | not yet | Room database, Retrofit sync client |
| `:app` | not yet | Compose UI, BLE foreground service |

`:core-mesh` deliberately has no Android dependency. Bluetooth cannot be
emulated, but relay *logic* does not need a radio: `MeshTransport` is an
interface that `VirtualMesh` implements in tests and the BLE service will
implement on-device. That is what makes proposal TESTS 3, 4 and 5 run as
ordinary JVM unit tests in milliseconds.

```bash
cd android
gradle :core-mesh:test
```

Target: **minSdk 26**, Android 12+ permission model
(`BLUETOOTH_SCAN` / `ADVERTISE` / `CONNECT` with `neverForLocation`) as the
primary path, legacy location permission as fallback.
