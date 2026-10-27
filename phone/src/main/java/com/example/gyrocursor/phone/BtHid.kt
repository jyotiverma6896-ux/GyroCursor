package com.example.gyrocursor.phone

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import java.util.concurrent.Executors

/** Phone ko Bluetooth HID (mouse + consumer keys + keyboard) device banata hai. */
@SuppressLint("MissingPermission")
class BtHid(private val ctx: Context, private val onState: (String) -> Unit) {

    companion object {
        private val DESCRIPTOR = intArrayOf(
            // Mouse, report id 1: 3 buttons, x, y, wheel
            0x05, 0x01, 0x09, 0x02, 0xA1, 0x01, 0x85, 0x01, 0x09, 0x01, 0xA1, 0x00,
            0x05, 0x09, 0x19, 0x01, 0x29, 0x03, 0x15, 0x00, 0x25, 0x01, 0x95, 0x03,
            0x75, 0x01, 0x81, 0x02, 0x95, 0x01, 0x75, 0x05, 0x81, 0x03,
            0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x09, 0x38, 0x15, 0x81, 0x25, 0x7F,
            0x75, 0x08, 0x95, 0x03, 0x81, 0x06, 0xC0, 0xC0,
            // Consumer control, report id 2: 16 bit usage (Home, Back ...)
            0x05, 0x0C, 0x09, 0x01, 0xA1, 0x01, 0x85, 0x02, 0x15, 0x00, 0x26, 0xFF,
            0x03, 0x19, 0x00, 0x2A, 0xFF, 0x03, 0x75, 0x10, 0x95, 0x01, 0x81, 0x00, 0xC0,
            // Keyboard, report id 3
            0x05, 0x01, 0x09, 0x06, 0xA1, 0x01, 0x85, 0x03, 0x05, 0x07, 0x19, 0xE0,
            0x29, 0xE7, 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x08, 0x81, 0x02,
            0x95, 0x01, 0x75, 0x08, 0x81, 0x01, 0x95, 0x06, 0x75, 0x08, 0x15, 0x00,
            0x25, 0x65, 0x05, 0x07, 0x19, 0x00, 0x29, 0x65, 0x81, 0x00, 0xC0
        ).map { it.toByte() }.toByteArray()

        const val HOME = 0x0223
        const val BACK = 0x0224
        const val KEY_BACKSPACE = 0x2A
    }

    private val exec = Executors.newSingleThreadExecutor()
    private val adapter: BluetoothAdapter? =
        (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private var hid: BluetoothHidDevice? = null
    @Volatile private var host: BluetoothDevice? = null

    val isConnected get() = host != null

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluginDevice: BluetoothDevice?, registered: Boolean) {
            onState(if (registered) "Ready. Ab TV se pair/connect karo" else "HID register nahi hua")
        }

        override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    host = device
                    onState("Connected ✔  ${device?.name ?: ""}")
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (device == host) host = null
                    onState("Disconnected")
                }
            }
        }
    }

    fun start() {
        val a = adapter
        if (a == null) { onState("Is phone me Bluetooth nahi hai"); return }
        if (!a.isEnabled) { onState("Pehle Bluetooth ON karo"); return }
        a.getProfileProxy(ctx, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                val h = proxy as BluetoothHidDevice
                hid = h
                val sdp = BluetoothHidDeviceAppSdpSettings(
                    "Gyro Mouse", "Phone gyro mouse", "GyroCursor",
                    BluetoothHidDevice.SUBCLASS1_COMBO, DESCRIPTOR
                )
                h.registerApp(sdp, null, null, exec, callback)
            }

            override fun onServiceDisconnected(profile: Int) {
                hid = null
                host = null
            }
        }, BluetoothProfile.HID_DEVICE)
    }

    fun pairedDevices(): List<BluetoothDevice> = adapter?.bondedDevices?.toList() ?: emptyList()

    fun connect(d: BluetoothDevice) {
        val h = hid
        if (h == null) { onState("HID abhi ready nahi"); return }
        onState("${d.name} se connect ho raha hai...")
        h.connect(d)
    }

    private fun send(id: Int, data: ByteArray) {
        val d = host ?: return
        exec.execute { try { hid?.sendReport(d, id, data) } catch (_: Exception) {} }
    }

    fun mouse(buttons: Int, dx: Int, dy: Int, wheel: Int = 0) {
        send(1, byteArrayOf(
            buttons.toByte(),
            dx.coerceIn(-127, 127).toByte(),
            dy.coerceIn(-127, 127).toByte(),
            wheel.coerceIn(-127, 127).toByte()
        ))
    }

    fun consumer(usage: Int) {
        send(2, byteArrayOf((usage and 0xFF).toByte(), (usage shr 8).toByte()))
        send(2, byteArrayOf(0, 0))
    }

    fun key(usage: Int, shift: Boolean = false) {
        send(3, byteArrayOf(if (shift) 0x02 else 0, 0, usage.toByte(), 0, 0, 0, 0, 0))
        send(3, ByteArray(8))
    }

    fun typeText(text: String) {
        for (c in text) {
            val (u, s) = map(c) ?: continue
            key(u, s)
        }
    }

    private fun map(c: Char): Pair<Int, Boolean>? = when (c) {
        in 'a'..'z' -> Pair(0x04 + (c - 'a'), false)
        in 'A'..'Z' -> Pair(0x04 + (c - 'A'), true)
        in '1'..'9' -> Pair(0x1E + (c - '1'), false)
        '0' -> Pair(0x27, false)
        ' ' -> Pair(0x2C, false)
        '\n' -> Pair(0x28, false)
        '-' -> Pair(0x2D, false); '_' -> Pair(0x2D, true)
        '=' -> Pair(0x2E, false); '+' -> Pair(0x2E, true)
        '[' -> Pair(0x2F, false); '{' -> Pair(0x2F, true)
        ']' -> Pair(0x30, false); '}' -> Pair(0x30, true)
        '\\' -> Pair(0x31, false); '|' -> Pair(0x31, true)
        ';' -> Pair(0x33, false); ':' -> Pair(0x33, true)
        '\'' -> Pair(0x34, false); '"' -> Pair(0x34, true)
        '`' -> Pair(0x35, false); '~' -> Pair(0x35, true)
        ',' -> Pair(0x36, false); '<' -> Pair(0x36, true)
        '.' -> Pair(0x37, false); '>' -> Pair(0x37, true)
        '/' -> Pair(0x38, false); '?' -> Pair(0x38, true)
        '!' -> Pair(0x1E, true); '@' -> Pair(0x1F, true); '#' -> Pair(0x20, true)
        '$' -> Pair(0x21, true); '%' -> Pair(0x22, true); '^' -> Pair(0x23, true)
        '&' -> Pair(0x24, true); '*' -> Pair(0x25, true); '(' -> Pair(0x26, true)
        ')' -> Pair(0x27, true)
        else -> null
    }

    fun close() {
        try { hid?.unregisterApp() } catch (_: Exception) {}
        try { adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, hid) } catch (_: Exception) {}
        exec.shutdown()
    }
}
