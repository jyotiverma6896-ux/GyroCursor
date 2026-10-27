package com.example.gyrocursor.phone

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
import kotlin.math.abs

@SuppressLint("MissingPermission")
class MainActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var status: TextView
    private lateinit var devices: LinearLayout
    private lateinit var sm: SensorManager
    private var gyro: Sensor? = null
    private lateinit var bt: BtHid
    private val handler = Handler(Looper.getMainLooper())

    private var sens = 5
    private var accDx = 0f
    private var accDy = 0f
    private var lastTs = 0L
    private var lastSend = 0L
    private var downMs = 0L
    private var moved = 0
    private var buttons = 0

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

    // ---- mouse helpers ----
    private fun click() {
        buttons = 1
        bt.mouse(buttons, 0, 0)
        handler.postDelayed({ buttons = 0; bt.mouse(0, 0, 0) }, 45)
    }

    private fun rightClick() {
        bt.mouse(2, 0, 0)
        handler.postDelayed({ bt.mouse(0, 0, 0) }, 45)
    }

    private fun longPress() {
        bt.mouse(1, 0, 0)
        handler.postDelayed({ bt.mouse(0, 0, 0) }, 700)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        prefs = getSharedPreferences("gyro", MODE_PRIVATE)
        sm = getSystemService(SENSOR_SERVICE) as SensorManager
        gyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        sens = prefs.getInt("sens", 5)
        bt = BtHid(this) { setStatus(it); runOnUiThread { refreshDevices() } }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(40), dp(12), dp(24))
        }

        status = TextView(this).apply { text = "Bluetooth permission do"; textSize = 16f }
        root.addView(status)

        root.addView(button("Phone ko discoverable karo (TV se pair ke liye)") {
            startActivity(
                Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                    .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120)
            )
        })
        root.addView(TextView(this).apply { text = "Paired devices (TV par tap karo):" })
        devices = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(devices)

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
            button("Click") { click() },
            button("Right click") { rightClick() },
            button("Long press") { longPress() }
        ))
        root.addView(row(
            button("Scroll ▲") { bt.mouse(0, 0, 0, 1) },
            button("Scroll ▼") { bt.mouse(0, 0, 0, -1) }
        ))
        root.addView(row(
            button("Back") { bt.consumer(BtHid.BACK) },
            button("Home") { bt.consumer(BtHid.HOME) }
        ))

        val kb = EditText(this).apply { hint = "TV ke text box me likhne ke liye (English)" }
        root.addView(kb)
        root.addView(row(
            button("Send") {
                val t = kb.text.toString()
                if (t.isNotEmpty()) bt.typeText(t)
                kb.setText("")
            },
            button("⌫") { bt.key(BtHid.KEY_BACKSPACE) },
            button("Enter") { bt.typeText("\n") }
        ))

        setContentView(ScrollView(this).apply { addView(root) })
        ensurePermission()
    }

    private fun ensurePermission() {
        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 1)
        } else {
            startBt()
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (res.isNotEmpty() && res[0] == PackageManager.PERMISSION_GRANTED) startBt()
        else setStatus("Bluetooth permission ke bina app nahi chalega")
    }

    private fun startBt() {
        bt.start()
        refreshDevices()
    }

    private fun refreshDevices() {
        if (!::devices.isInitialized) return
        devices.removeAllViews()
        try {
            bt.pairedDevices().forEach { d ->
                devices.addView(button("${d.name ?: "Unknown"}  (${d.address})") { bt.connect(d) })
            }
        } catch (_: Exception) {
        }
    }

    override fun onResume() {
        super.onResume()
        refreshDevices()
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
                    bt.mouse(buttons, ix, iy)
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
        if (released && moved < 6 && SystemClock.uptimeMillis() - downMs < 250) click()
    }

    override fun onPause() {
        super.onPause()
        sm.unregisterListener(gyroListener)
    }

    override fun onDestroy() {
        super.onDestroy()
        bt.close()
    }
}
