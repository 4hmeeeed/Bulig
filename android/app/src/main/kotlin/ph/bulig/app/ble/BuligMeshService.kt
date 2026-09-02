package ph.bulig.app.ble

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import android.os.ParcelUuid
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.UUID
import ph.bulig.app.R
import ph.bulig.mesh.ble.AdvertisementPayload
import ph.bulig.mesh.ble.BleAction
import ph.bulig.mesh.ble.BleEvent
import ph.bulig.mesh.ble.BleSession
import ph.bulig.mesh.ble.DigestCodec
import ph.bulig.mesh.ble.GattContract
import ph.bulig.mesh.ble.InboundResult
import ph.bulig.mesh.ble.NodeInfo
import ph.bulig.mesh.ble.NodeInfoCodec
import ph.bulig.mesh.ble.PacketReceiver
import ph.bulig.mesh.model.MeshPacket

/**
 * The BLE relay, as a foreground service.
 *
 * **Not yet compiled.** Written against the Android documentation, never against
 * a compiler. See `android/BUILDING.md`.
 *
 * Deliberately thin. Every decision — what to send, in what order, when to stop
 * — lives in [BleSession] in `:core-mesh`, which has 23 tests. This class turns
 * that state machine's actions into GATT calls and feeds the results back. If a
 * rule starts appearing in this file, it is in the wrong place: nothing here can
 * be tested without two phones in a room.
 *
 * Runs both GATT roles at once. A device that only scans can never receive; a
 * device that only advertises can never forward.
 *
 * @see docs/06-ble-protocol.md 6.1
 */
// Every Bluetooth call below is guarded by hasPermissions() at startup and by a
// SecurityException catch at the call site. Lint cannot see either, so it flags
// all of them; suppressing once here beats scattering annotations that would
// suggest the guards are per-call rather than structural.
@SuppressLint("MissingPermission")
class BuligMeshService : Service() {

    private val serviceUuid = UUID.fromString(GattContract.SERVICE_UUID)

    private val bluetoothManager: BluetoothManager? by lazy {
        ContextCompat.getSystemService(this, BluetoothManager::class.java)
    }
    private val adapter: BluetoothAdapter? by lazy { bluetoothManager?.adapter }

    /** Packets this device is carrying. Supplied by the repository once wired. */
    private var heldPackets: List<MeshPacket> = emptyList()

    private val activeSessions = mutableMapOf<String, BleSession>()

    private var gattServer: BluetoothGattServer? = null

    /**
     * The receive path. Every decision it makes is tested in `:core-mesh`
     * (24 tests); this class only carries bytes to it and notifications back.
     *
     * **Still null.** Constructing it needs a `MeshNode`, which needs the
     * persistent store — task 24. Until then the GATT server answers reads and
     * accepts writes but stores nothing, so a peer's frames are dropped after
     * arriving. The null check at the write site makes that a no-op rather than
     * a crash, but it is not the finished state: the mesh is wired end to end
     * everywhere except this one reference.
     */
    private var receiver: PacketReceiver? = null

    /** Peers that subscribed to ACK notifications, by address. */
    private val ackSubscribers = mutableSetOf<String>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startRelayForeground()

        if (!hasPermissions()) {
            // Relaying without permission is not possible, and crashing a
            // foreground service in a disaster app is worse than stopping.
            stopSelf()
            return
        }

        startGattServer()
        startAdvertising()
        startScanning()
    }

    // --- peripheral role --------------------------------------------------

    /**
     * Advertises the Bulig service so other devices can find this one.
     *
     * Some cheap chipsets cannot advertise at all. That is detected here and the
     * device degrades to scan-only rather than failing: a phone that can only
     * receive is still a useful relay node.
     */
    private fun startAdvertising() {
        val advertiser = adapter?.bluetoothLeAdvertiser
        if (advertiser == null) {
            onAdvertisingUnsupported()
            return
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .build()

        val payload = AdvertisementPayload(
            hasInternet = hasInternet(),
            supportsAdvertising = true,
            pendingCount = heldPackets.size,
        )

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false) // Never broadcast a resident's device name.
            .addServiceUuid(ParcelUuid(serviceUuid))
            .addManufacturerData(MANUFACTURER_ID, payload.encode())
            .build()

        try {
            advertiser.startAdvertising(settings, data, advertiseCallback)
        } catch (e: SecurityException) {
            stopSelf()
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            MeshRadioStatus.reportAdvertisingActive()
        }

        override fun onStartFailure(errorCode: Int) {
            if (errorCode == ADVERTISE_FAILED_FEATURE_UNSUPPORTED) {
                onAdvertisingUnsupported()
            }
        }
    }

    /** Surfaced to the user rather than hidden: it halves what this phone can do. */
    private fun onAdvertisingUnsupported() {
        MeshRadioStatus.reportAdvertisingUnsupported()
    }

    // --- GATT server (the receive path) -----------------------------------

    /**
     * Hosts the four characteristics so a peer that connects has something to
     * read and somewhere to write.
     *
     * Without this the device advertises, gets connected to, and then answers
     * nothing — two Bulig phones would find each other and exchange no reports
     * at all. Advertising without a server is the mesh's most convincing way of
     * looking healthy while doing nothing.
     */
    private fun startGattServer() {
        val manager = bluetoothManager ?: return

        val server = try {
            manager.openGattServer(this, gattServerCallback)
        } catch (e: SecurityException) {
            null
        } ?: return

        val service = BluetoothGattService(serviceUuid, BluetoothGattService.SERVICE_TYPE_PRIMARY)

        service.addCharacteristic(
            BluetoothGattCharacteristic(
                UUID.fromString(GattContract.CHAR_NODE_INFO),
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ,
            )
        )
        service.addCharacteristic(
            BluetoothGattCharacteristic(
                UUID.fromString(GattContract.CHAR_DIGEST),
                BluetoothGattCharacteristic.PROPERTY_READ or
                    BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ,
            )
        )
        service.addCharacteristic(
            BluetoothGattCharacteristic(
                UUID.fromString(GattContract.CHAR_PACKET_IN),
                BluetoothGattCharacteristic.PROPERTY_WRITE or
                    BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE,
            )
        )

        // ACK carries the per-packet verdict back, so the sender knows whether
        // to stop offering a packet to this peer. A notify characteristic needs
        // the standard Client Characteristic Configuration descriptor or no
        // peer can ever subscribe to it.
        val ack = BluetoothGattCharacteristic(
            UUID.fromString(GattContract.CHAR_ACK),
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ,
        )
        ack.addDescriptor(
            BluetoothGattDescriptor(
                CCC_DESCRIPTOR_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or
                    BluetoothGattDescriptor.PERMISSION_WRITE,
            )
        )
        service.addCharacteristic(ack)

        try {
            server.addService(service)
        } catch (e: SecurityException) {
            return
        }

        gattServer = server
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic,
        ) {
            val value = when (characteristic.uuid.toString().lowercase()) {
                GattContract.CHAR_NODE_INFO.lowercase() -> NodeInfoCodec.encode(nodeInfo())
                GattContract.CHAR_DIGEST.lowercase() -> DigestCodec.encode(currentDigest())
                else -> ByteArray(0)
            }

            // A long value is read in slices; the peer asks again with a higher
            // offset until it has all of it. Ignoring the offset would send the
            // same first slice forever.
            val slice = if (offset >= value.size) ByteArray(0) else value.copyOfRange(offset, value.size)

            respond(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, slice)
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            if (responseNeeded) {
                respond(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            }

            if (!characteristic.uuid.toString().equals(GattContract.CHAR_PACKET_IN, true)) return

            val outcome = receiver?.onFrameWritten(
                peer = device.address,
                frame = value,
                nowMs = System.currentTimeMillis(),
            ) ?: return

            // Buffering means the message is still arriving — there is nothing
            // truthful to acknowledge yet.
            if (outcome is InboundResult.Acknowledge) {
                notifyAck(device, outcome.code.wire)
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            if (descriptor.uuid == CCC_DESCRIPTOR_UUID) {
                val enabling = value.contentEquals(
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                )
                if (enabling) ackSubscribers += device.address else ackSubscribers -= device.address
            }

            if (responseNeeded) {
                respond(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            }
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState != BluetoothGatt.STATE_CONNECTED) {
                ackSubscribers -= device.address
                // Frees any half-received message the departing peer left behind.
                receiver?.onPeerDisconnected(System.currentTimeMillis())
            }
        }
    }

    private fun respond(
        device: BluetoothDevice,
        requestId: Int,
        status: Int,
        offset: Int,
        value: ByteArray?,
    ) {
        try {
            gattServer?.sendResponse(device, requestId, status, offset, value)
        } catch (e: SecurityException) {
            // Permission revoked mid-session. The peer times out, which is a
            // state BLE senders already handle.
        }
    }

    /** Sends one packet's verdict back to the peer that wrote it. */
    private fun notifyAck(device: BluetoothDevice, code: Byte) {
        if (device.address !in ackSubscribers) return

        val characteristic = gattServer
            ?.getService(serviceUuid)
            ?.getCharacteristic(UUID.fromString(GattContract.CHAR_ACK))
            ?: return

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gattServer?.notifyCharacteristicChanged(device, characteristic, false, byteArrayOf(code))
            } else {
                @Suppress("DEPRECATION")
                characteristic.value = byteArrayOf(code)
                @Suppress("DEPRECATION")
                gattServer?.notifyCharacteristicChanged(device, characteristic, false)
            }
        } catch (e: SecurityException) {
            // As above: the sender's own timeout handles it.
        }
    }

    /**
     * What this device tells a peer about itself.
     *
     * TO BE WIRED: [deviceId] must come from the persisted per-install
     * identifier once registration exists. A constant here would make every
     * install claim the same identity.
     */
    private fun nodeInfo() = NodeInfo(
        deviceId = ph.bulig.mesh.model.DeviceId("dev-local-prototype"),
        protocolVersion = GattContract.PROTOCOL_VERSION,
        hasInternet = hasInternet(),
        pendingCount = heldPackets.size,
    )

    /** What this device already holds, so a peer does not offer it twice. */
    private fun currentDigest() =
        ph.bulig.mesh.digest.BloomDigest.of(heldPackets.map { it.packetId })

    // --- central role -----------------------------------------------------

    private fun startScanning() {
        val scanner = adapter?.bluetoothLeScanner ?: return

        // Filtering on our own service UUID is what lets the manifest claim
        // neverForLocation: the app never sees devices that are not Bulig.
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(serviceUuid))
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            scanner.startScan(listOf(filter), settings, scanCallback)
        } catch (e: SecurityException) {
            stopSelf()
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val advert = result.scanRecord
                ?.getManufacturerSpecificData(MANUFACTURER_ID)
                ?.let { AdvertisementPayload.decode(it) }
                ?: return

            // A peer that can reach the server ends a packet's journey rather
            // than extending it, so it is worth connecting to first.
            if (activeSessions.size >= GattContract.MAX_CONCURRENT_CONNECTIONS &&
                !advert.hasInternet
            ) {
                return
            }

            connectTo(result.device)
        }
    }

    // --- one encounter ----------------------------------------------------

    private fun connectTo(device: BluetoothDevice) {
        val address = device.address
        if (activeSessions.containsKey(address)) return

        val session = BleSession(
            heldPackets = heldPackets,
            isForwardable = { packet, _ -> !packet.isTerminal },
        )
        activeSessions[address] = session

        try {
            device.connectGatt(this, false, gattCallback)
        } catch (e: SecurityException) {
            activeSessions.remove(address)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val session = activeSessions[gatt.device.address] ?: return

            if (newState == BluetoothGatt.STATE_CONNECTED) {
                perform(gatt, session.start(), session)
            } else {
                // Peers walk out of range constantly; this is ordinary.
                perform(gatt, session.onEvent(BleEvent.Failed("disconnected")), session)
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            val session = activeSessions[gatt.device.address] ?: return
            perform(gatt, session.onEvent(BleEvent.MtuNegotiated(mtu)), session)
        }

        /**
         * The pre-API-33 form. The platform calls exactly one of these two
         * depending on the device's release, so both must exist or the app
         * reads nothing at all on older phones.
         */
        @Deprecated("Superseded by the ByteArray overload on API 33+")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            onCharacteristicRead(gatt, characteristic, characteristic.value ?: ByteArray(0), status)
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            val session = activeSessions[gatt.device.address] ?: return

            if (status != BluetoothGatt.GATT_SUCCESS) {
                perform(gatt, session.onEvent(BleEvent.Failed("read failed ($status)")), session)
                return
            }

            // Decoding lives in :core-mesh; this only routes the bytes by which
            // characteristic answered.
            val event = when (characteristic.uuid.toString().lowercase()) {
                GattContract.CHAR_NODE_INFO.lowercase() -> {
                    val info = NodeInfoCodec.decode(value)
                    if (info == null) {
                        BleEvent.Failed("unreadable node info")
                    } else {
                        BleEvent.NodeInfoRead(info.deviceId, info.protocolVersion)
                    }
                }

                // Null is not an error here: the session treats a peer that will
                // not answer as one holding nothing, and offers it everything.
                GattContract.CHAR_DIGEST.lowercase() ->
                    BleEvent.DigestRead(DigestCodec.decode(value))

                else -> return
            }

            perform(gatt, session.onEvent(event), session)
        }
    }

    /**
     * Carries out one action from the state machine.
     *
     * The only branching in this class, and deliberately mechanical: the
     * decisions were all made before we got here.
     */
    private fun perform(gatt: BluetoothGatt, action: BleAction, session: BleSession) {
        try {
            when (action) {
                is BleAction.RequestMtu -> gatt.requestMtu(GattContract.PREFERRED_MTU)
                is BleAction.ReadNodeInfo -> readCharacteristic(gatt, GattContract.CHAR_NODE_INFO)
                is BleAction.ReadDigest -> readCharacteristic(gatt, GattContract.CHAR_DIGEST)
                is BleAction.SendPacket -> writeFrames(gatt, action)
                is BleAction.Disconnect -> {
                    activeSessions.remove(gatt.device.address)
                    gatt.close()
                }
            }
        } catch (e: SecurityException) {
            activeSessions.remove(gatt.device.address)
        }
    }

    private fun readCharacteristic(gatt: BluetoothGatt, uuid: String) {
        gatt.getService(serviceUuid)
            ?.getCharacteristic(UUID.fromString(uuid))
            ?.let { gatt.readCharacteristic(it) }
    }

    /** The framing is already done — [BleSession] sized these for the negotiated MTU. */
    private fun writeFrames(gatt: BluetoothGatt, action: BleAction.SendPacket) {
        val characteristic = gatt.getService(serviceUuid)
            ?.getCharacteristic(UUID.fromString(GattContract.CHAR_PACKET_IN))
            ?: return

        action.frames.forEach { frame ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(
                    characteristic, frame,
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
                )
            } else {
                @Suppress("DEPRECATION")
                characteristic.value = frame
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(characteristic)
            }
        }
    }

    // --- housekeeping -----------------------------------------------------

    /**
     * Starts the foreground notification, with the service type API 34+ demands.
     *
     * The type must match the manifest declaration exactly or the platform
     * throws rather than degrading, so this is not a defensive nicety.
     */
    private fun startRelayForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun hasPermissions(): Boolean {
        val required = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
            )
        } else {
            listOf(Manifest.permission.ACCESS_COARSE_LOCATION)
        }

        return required.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Whether this device can actually reach the server.
     *
     * `NET_CAPABILITY_VALIDATED` rather than merely connected: a phone attached
     * to a captive portal or a dead barangay uplink would otherwise advertise
     * itself as a route out, and peers would hand it their packets to end a
     * journey it cannot end.
     */
    private fun hasInternet(): Boolean {
        val manager = ContextCompat.getSystemService(this, ConnectivityManager::class.java)
            ?: return false
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false

        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /**
     * The persistent notification.
     *
     * Required for a foreground service, and honest about what it is doing —
     * a resident should be able to see why an emergency app is using the radio.
     */
    private fun buildNotification(): Notification {
        val manager = ContextCompat.getSystemService(this, NotificationManager::class.java)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager?.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Bulig mesh",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Carries emergency reports to and from nearby phones."
                }
            )
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Bulig is relaying reports")
            .setContentText("Carrying emergency reports for people nearby.")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
            adapter?.bluetoothLeScanner?.stopScan(scanCallback)
            gattServer?.close()
        } catch (e: SecurityException) {
            // Losing permission while shutting down changes nothing worth handling.
        }
    }

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "bulig_mesh"

        /**
         * TO BE REGISTERED: 0xFFFF is the Bluetooth SIG's reserved test id.
         * Fine for a capstone prototype, not for anything published.
         */
        private const val MANUFACTURER_ID = 0xFFFF

        /**
         * The Bluetooth SIG's Client Characteristic Configuration descriptor.
         * Fixed by the specification — a notify characteristic without it cannot
         * be subscribed to by any peer.
         */
        private val CCC_DESCRIPTOR_UUID: UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, BuligMeshService::class.java),
            )
        }
    }
}
