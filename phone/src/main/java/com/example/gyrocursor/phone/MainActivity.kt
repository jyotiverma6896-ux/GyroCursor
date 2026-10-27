package com.example.gyrocursor.phone

import android.app.Activity
import android.content.SharedPreferences
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.net.Inet4Address
import kotlin.math.abs

class MainActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var status: TextView
    private lateinit var ipField: EditText
    private lateinit var pinField: EditText
    private lateinit var found: LinearLayout
    private lateinit var sm: SensorManager
    private var gyro: Sensor? = null
    private val sender = Sender()

    // gyro state
    private var sens = 5
    private var accDx = 0f
    private var accDy = 0f
    private var lastTs = 0L
    private var lastSend = 0L
    private var downMs = 0L
    private var moved = 0

    // discovery
    private val nsd by lazy { getSystemService(NSD_SERVICE) as NsdManager }
    private val queue = java.util.ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private val shownIps = HashSet<String>()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEach { addView(it, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)) }
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()
    private fun setStatus(t: String) = runOnUiThread { status.text = t }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        prefs = getSharedPreferences("gyro", MODE_PRIVATE)
        sm = getSystemService(SENSOR_SERVICE) as SensorManager
        gyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        sens = prefs.getInt("sens", 5)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(40), dp(12), dp(24))
        }

        status = TextView(this).apply { text = "TV ka IP aur PIN daalo"; textSize = 16f }
        root.addView(status)

        ipField = EditText(this).apply {
            hint = "TV ka IP (TV app me dikhta hai)"
            setText(prefs.getString("ip", ""))
            inputType = InputType.TYPE_CLASS_PHONE
        }
        root.addView(ipField)
        pinField = EditText(this).apply {
            hint = "TV ka PIN (4 digit)"
            setText(prefs.getString("pin", ""))
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        root.addView(pinField)
        root.addView(button("Connect") { connect() })
        found = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(found)

        // gyro pad
        val pad = Button(this).apply {
            text = "DABAKE RAKHO + PHONE GHUMAO\n(sirf tap = click)"
            textSize = 16f
            setOnTouchListener { v, ev ->
                when (ev.action) {
                    MotionEvent.ACTION_DOWN -> { v.isPressed = true; startGyro(); true }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.isPressed = false
                        stopGyro(ev.action == MotionEvent.ACTION_UP)
                        true
                    }
                    else -> true
                }
            }
        }
        root.addView(pad, LinearLayout.LayoutParams(MATCH_PARENT, dp(200)).apply { topMargin = dp(12) })

        root.addView(TextView(this).apply { text = "Speed (zyada = cursor tez)" })
        root.addView(SeekBar(this).apply {
            max = 9
            progress = sens - 1
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) {
                    sens = p + 1
                    prefs.edit().putInt("sens", sens).apply()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        })

        root.addView(row(
            button("Click") { sender.send("C") },
            button("Long press") { sender.send("L") },
            button("Center") { sender.send("Z") }
        ))
        root.addView(row(
            button("Scroll ▲") { sender.send("S u") },
            button("Scroll ▼") { sender.send("S d") }
        ))
        root.addView(row(
            button("Back") { sender.send("B") },
            button("Home") { sender.send("H") }
        ))

        val kb = EditText(this).apply { hint = "TV ke text box me likhne ke liye" }
        root.addView(kb)
        root.addView(row(
            button("Send") {
                val t = kb.text.toString()
                if (t.isNotEmpty()) sender.send("T $t")
                kb.setText("")
            },
            button("⌫") { sender.send("X") }
        ))

        setContentView(ScrollView(this).apply { addView(root) })

        try {
            nsd.discoverServices("_gyrocursor._udp", NsdManager.PROTOCOL_DNS_SD, discListener)
        } catch (_: Exception) {
        }

        if (ipField.text.isNotEmpty() && pinField.text.isNotEmpty()) connect()
    }

    private fun connect() {
        val ip = ipField.text.toString().trim()
        val pin = pinField.text.toString().trim()
        if (ip.isEmpty() || pin.isEmpty()) { toast("IP aur PIN dono daalo"); return }
        prefs.edit().putString("ip", ip).putString("pin", pin).apply()
        sender.host = ip
        sender.pin = pin
        setStatus("TV ko dhundh raha hoon...")
        sender.ping { r ->
            setStatus(
                when {
                    r == "OK" -> "Connected ✔  $ip"
                    r == "BADPIN" -> "PIN galat hai"
                    r == "TIMEOUT" -> "TV jawab nahi de raha. TV par app + service on hai? Same WiFi?"
                    else -> "Error: $r"
                }
            )
        }
    }

    // ---------- gyro mouse ----------

    private val gyroListener = object : SensorEventListener {
        override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        override fun onSensorChanged(e: SensorEvent) {
            if (lastTs == 0L) { lastTs = e.timestamp; return }
            val dt = (e.timestamp - lastTs) / 1_000_000_000f
            lastTs = e.timestamp
            var wx = e.values[0]
            var wz = e.values[2]
            if (abs(wx) < 0.08f) wx = 0f
            if (abs(wz) < 0.08f) wz = 0f
            val k = 1100f * sens / 5f
            accDx += -wz * k * dt
            accDy += -wx * k * dt
            val now = SystemClock.uptimeMillis()
            if (now - lastSend >= 12) {
                val ix = accDx.toInt()
                val iy = accDy.toInt()
                if (ix != 0 || iy != 0) {
                    sender.send("M $ix $iy")
                    accDx -= ix
                    accDy -= iy
                    moved += abs(ix) + abs(iy)
                }
                lastSend = now
            }
        }
    }

    private fun startGyro() {
        if (gyro == null) { toast("Is phone me gyroscope nahi mila"); return }
        downMs = SystemClock.uptimeMillis()
        moved = 0
        accDx = 0f
        accDy = 0f
        lastTs = 0L
        sm.registerListener(gyroListener, gyro, SensorManager.SENSOR_DELAY_GAME)
    }

    private fun stopGyro(released: Boolean) {
        sm.unregisterListener(gyroListener)
        if (released && moved < 6 && SystemClock.uptimeMillis() - downMs < 250) sender.send("C")
    }

    // ---------- discovery ----------

    private val discListener = object : NsdManager.DiscoveryListener {
        override fun onStartDiscoveryFailed(t: String?, c: Int) {}
        override fun onStopDiscoveryFailed(t: String?, c: Int) {}
        override fun onDiscoveryStarted(t: String?) {}
        override fun onDiscoveryStopped(t: String?) {}
        override fun onServiceLost(i: NsdServiceInfo?) {}
        override fun onServiceFound(i: NsdServiceInfo) {
            synchronized(queue) { queue.add(i) }
            resolveNext()
        }
    }

    @Synchronized
    private fun resolveNext() {
        if (resolving) return
        val next = synchronized(queue) { queue.poll() } ?: return
        resolving = true
        try {
            nsd.resolveService(next, object : NsdManager.ResolveListener {
                override fun onResolveFailed(i: NsdServiceInfo?, c: Int) {
                    resolving = false
                    resolveNext()
                }
                override fun onServiceResolved(i: NsdServiceInfo) {
                    resolving = false
                    val h = i.host
                    if (h is Inet4Address) addFound(i.serviceName, h.hostAddress ?: "")
                    resolveNext()
                }
            })
        } catch (_: Exception) {
            resolving = false
        }
    }

    private fun addFound(name: String, ip: String) {
        if (ip.isEmpty()) return
        runOnUiThread {
            if (shownIps.add(ip)) {
                found.addView(button("$name  ($ip)") {
                    ipField.setText(ip)
                    connect()
                })
            }
        }
    }

    override fun onPause() {
        super.onPause()
        sm.unregisterListener(gyroListener)
    }

    override fun onDestroy() {
        super.onDestroy()
        sender.close()
        try { nsd.stopServiceDiscovery(discListener) } catch (_: Exception) {}
    }
}
