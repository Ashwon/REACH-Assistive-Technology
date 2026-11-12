package com.reach.app.haptic

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import java.util.UUID

/**
 * The ESP32-S3 vibration band over BLE (Nordic UART Service). One per app, so the link survives the setup screen.
 * Everything here (GATT callbacks, BandSession's write queue, HapticPlayer and its timers) runs on one "reach-band"
 * thread: the camera and detector threads only post to it and never wait on Bluetooth.
 *
 * Protocol (the existing firmware's): RX takes "MOTOR,POWER,DURATION_MS"; TX may notify text. The write type follows
 * RX's own properties: with response when it supports that, so every pulse is acknowledged by the band's BLE stack
 * (which proves delivery, not vibration).
 */
@SuppressLint("MissingPermission") // every Bluetooth call is reached only after hasPermission()
class BleBand private constructor(private val app: Context) : HapticLink {
    companion object {
        const val NAME = "REACH_Haptic_Device"
        val SERVICE: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
        val RX: UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
        val TX: UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        const val SCAN_MS = 12_000L
        const val CONNECT_MS = 12_000L
        private const val TAG = "REACH"

        @Volatile private var instance: BleBand? = null
        fun get(c: Context) = instance ?: synchronized(this) { instance ?: BleBand(c.applicationContext).also { instance = it } }

        /** The runtime permissions BLE needs on this Android version. */
        val permissions: Array<String> = if (Build.VERSION.SDK_INT >= 31)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        fun hasPermission(c: Context) = permissions.all { c.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    }

    private val h = Handler(HandlerThread("reach-band").apply { start() }.looper)
    private val main = Handler(Looper.getMainLooper())
    private fun now() = SystemClock.uptimeMillis()
    private val session: BandSession = BandSession(::write, onLost = { player.stop(now()) })
    private val player: HapticPlayer = HapticPlayer { session.send(it, now()) }

    /** Live guidance may drive the motors: only once setup confirmed this band; never during setup or when skipped. */
    @Volatile var liveEnabled = false
        set(v) { if (field == v) return; field = v; if (!v) h.post { player.stop(now()); pump() } }
    /** Main thread: called after every state change (setup screen, settings sheet). */
    @Volatile var onChange: (() -> Unit)? = null
    /** The band connected now, or last: the setup remembers which band it tested. */
    @Volatile var address: String? = null; private set

    private var gatt: BluetoothGatt? = null
    private var rx: BluetoothGattCharacteristic? = null
    private var writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
    private var scanning = false
    private var wanted = false

    val state get() = session.state
    /** The raw link: connected with the REACH service found (setup uses this). */
    val ready get() = session.state == BandState.READY
    /** Live guidance is using the band: linked, and setup confirmed this band. */
    override val connected get() = liveEnabled && ready
    override val status get() = session.state.words + (if (session.detail.isEmpty()) "" else " · ${session.detail}") +
        " · motors ${session.motors}" + (session.lastFromBand?.let { " · band said \"$it\"" } ?: "") +
        " · writes ${session.written} ok, ${session.failed} failed or dropped"

    override fun request(cue: HapticCue?) {
        h.post {
            if (!liveEnabled) return@post
            if (cue != player.desired) Log.i(TAG, "band cue ${player.desired} -> $cue")
            player.set(cue, now()); pump()
        }
    }

    override fun stop() { h.post { player.stop(now()); pump() } }

    /** Setup screen practice: plays [pulses] once. It never goes through ReachCore, so it cannot complete or claim a task. */
    fun practice(pulses: List<Pulse>) { h.post { player.practice(pulses, now()); pump() } }

    /** Scan for the band and connect. Without [force], a link that is up or being set up is left alone. */
    fun connect(force: Boolean = false) {
        h.post {
            val busy = session.state in setOf(BandState.READY, BandState.SCANNING, BandState.FOUND, BandState.CONNECTING)
            if (busy && !force) return@post
            wanted = true; h.removeCallbacks(reconnect); session.resetAttempts(); begin(direct = false)
        }
    }

    /** Stop the motors, then drop the link and stay disconnected. */
    fun disconnect() {
        h.post {
            wanted = false; h.removeCallbacks(reconnect); player.stop(now()); pump()
            h.postDelayed({ if (!wanted) { close(); session.onDisconnected(false, "disconnected"); changed() } }, 300) // let STOP go out
        }
    }

    private fun adapter() = app.getSystemService(BluetoothManager::class.java)?.adapter

    private fun begin(direct: Boolean) {
        val adapter = adapter()
        when {
            adapter == null || !app.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE) -> session.status(BandState.NO_BLUETOOTH)
            !hasPermission(app) -> session.status(BandState.NEEDS_PERMISSION)
            !adapter.isEnabled -> session.status(BandState.BLUETOOTH_OFF)
            else -> {
                val known = address?.takeIf { direct }?.let { runCatching { adapter.getRemoteDevice(it) }.getOrNull() }
                if (known != null) connectTo(known, session.detail) else scan()
            }
        }
        changed()
    }

    private fun scan() {
        close()
        val scanner = adapter()?.bluetoothLeScanner ?: return session.status(BandState.FAILED, "Bluetooth scanner unavailable")
        // Either the name or the service is enough to report it; the service is checked again after connecting.
        val filters = listOf(ScanFilter.Builder().setDeviceName(NAME).build(),
            ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE)).build())
        session.status(BandState.SCANNING, "looking for $NAME")
        scanning = true
        scanner.startScan(filters, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCb)
        h.postDelayed(scanTimeout, SCAN_MS)
    }

    private val scanTimeout = Runnable {
        if (!scanning) return@Runnable
        stopScan(); session.status(BandState.FAILED, "$NAME not found nearby. Is it switched on and close?"); changed()
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false; h.removeCallbacks(scanTimeout)
        runCatching { adapter()?.bluetoothLeScanner?.stopScan(scanCb) }
    }

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, r: ScanResult) { h.post { found(r) } }
        override fun onScanFailed(errorCode: Int) {
            h.post { stopScan(); session.status(BandState.FAILED, "scan failed (error $errorCode)"); changed() }
        }
    }

    private fun found(r: ScanResult) {
        if (!scanning) return
        val rec = r.scanRecord
        if (rec?.deviceName != NAME && rec?.serviceUuids?.contains(ParcelUuid(SERVICE)) != true) return
        stopScan()
        session.status(BandState.FOUND, r.device.address); changed()
        connectTo(r.device, r.device.address)
    }

    private fun connectTo(d: BluetoothDevice, detail: String) {
        close()
        session.status(BandState.CONNECTING, detail)
        address = d.address
        gatt = d.connectGatt(app, false, cb, BluetoothDevice.TRANSPORT_LE)
        h.removeCallbacks(connectTimeout); h.postDelayed(connectTimeout, CONNECT_MS)
        changed()
    }

    private val connectTimeout = Runnable { if (gatt != null && session.state != BandState.READY) linkDown("connection timed out") }
    private val reconnect = Runnable { if (wanted && session.state == BandState.CONNECTING) begin(direct = true) }
    private val writeTimeout = Runnable { if (session.writeTimedOut(now())) linkDown("the band stopped answering writes") }
    private val tick = Runnable { pump() }

    /** GATT callbacks arrive on a binder thread: hop to the band thread, and ignore any GATT that was already closed. */
    private fun on(g: BluetoothGatt, f: () -> Unit) { h.post { if (g === gatt) { f(); changed() } } }

    private val cb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) = on(g) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                if (!g.discoverServices()) linkDown("service discovery could not start")
            } else if (newState != BluetoothProfile.STATE_CONNECTED) {
                linkDown(if (status == BluetoothGatt.GATT_SUCCESS) "disconnected" else "GATT error $status")
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) = on(g) { discovered(g, status) }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) = on(g) {
            if (status != BluetoothGatt.GATT_SUCCESS) Log.w(TAG, "band TX notifications not enabled (status $status)")
            ready(g)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) = on(g) {
            h.removeCallbacks(writeTimeout)
            session.onWritten(status == BluetoothGatt.GATT_SUCCESS, now()); pump()
        }

        // API 33+ calls this one; below 33 only the deprecated one below exists.
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) = on(g) { notified(value) }

        @Deprecated("API < 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION") val v = c.value?.copyOf() ?: return // read now: the characteristic is reused
            on(g) { notified(v) }
        }
    }

    private fun discovered(g: BluetoothGatt, status: Int) {
        if (status != BluetoothGatt.GATT_SUCCESS) return linkDown("service discovery failed (status $status)")
        val svc = g.getService(SERVICE) ?: return fail("this device has no REACH UART service")
        val r = svc.getCharacteristic(RX) ?: return fail("the band has no RX characteristic")
        writeType = when {
            r.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0 -> BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            r.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0 -> BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            else -> return fail("the band's RX characteristic is not writable")
        }
        rx = r
        Log.i(TAG, "band ${g.device.address}: RX props=0x${Integer.toHexString(r.properties)} " +
            "write=${if (writeType == BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) "with response" else "no response"}")
        val t = svc.getCharacteristic(TX)
        val cccd = t?.getDescriptor(CCCD)
        if (t != null && cccd != null && t.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 &&
            g.setCharacteristicNotification(t, true)) {
            val on = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            val started = if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(cccd, on) == BluetoothStatusCodes.SUCCESS
                else @Suppress("DEPRECATION") run { cccd.value = on; g.writeDescriptor(cccd) }
            if (started) return // ready() follows onDescriptorWrite: no RX write may overlap this GATT operation
        }
        ready(g)
    }

    private fun ready(g: BluetoothGatt) {
        if (session.state == BandState.READY) return
        h.removeCallbacks(connectTimeout)
        address = g.device.address
        session.onReady(now()) // its first write is STOP
        pump()
    }

    private fun notified(v: ByteArray) {
        val text = String(v, Charsets.US_ASCII).trim()
        Log.i(TAG, "band says \"$text\"")
        session.onNotify(text)
    }

    /** The wrong device or firmware: no automatic retry. */
    private fun fail(why: String) {
        wanted = false; close(); session.status(BandState.FAILED, why)
    }

    private fun linkDown(why: String) {
        Log.w(TAG, "band link down: $why")
        h.removeCallbacks(connectTimeout); h.removeCallbacks(writeTimeout)
        close()
        session.onDisconnected(wanted, why)?.let { h.postDelayed(reconnect, it) }
        changed()
    }

    private fun close() {
        stopScan()
        gatt?.let { runCatching { it.disconnect() }; runCatching { it.close() } }
        gatt = null; rx = null
    }

    /** Band thread: one GATT write, started now. */
    private fun write(payload: String): Boolean {
        val g = gatt; val c = rx
        if (g == null || c == null) return false
        val bytes = payload.toByteArray(Charsets.US_ASCII)
        val ok = if (Build.VERSION.SDK_INT >= 33) g.writeCharacteristic(c, bytes, writeType) == BluetoothStatusCodes.SUCCESS
            else @Suppress("DEPRECATION") run { c.writeType = writeType; c.value = bytes; g.writeCharacteristic(c) }
        if (ok) { h.removeCallbacks(writeTimeout); h.postDelayed(writeTimeout, BandSession.WRITE_TIMEOUT_MS + 50) }
        else Log.w(TAG, "band write \"$payload\" could not start")
        return ok
    }

    /** Runs the player's timer: sends due pulses, then sleeps until the next one. */
    private fun pump() {
        h.removeCallbacks(tick)
        player.tick(now())?.let { h.postAtTime(tick, it) }
    }

    private fun changed() { main.post { onChange?.invoke() } }
}
