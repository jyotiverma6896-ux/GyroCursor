package com.example.gyrocursor.tv

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.net.DatagramPacket
import java.net.DatagramSocket

const val PORT = 9876

/** Full-screen transparent view that only draws the arrow. */
class CursorView(ctx: Context) : View(ctx) {
    var cx = 0f
    var cy = 0f
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); style = Paint.Style.FILL }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF000000.toInt(); style = Paint.Style.STROKE; strokeWidth = 3f
    }
    private val s = resources.displayMetrics.density * 1.6f
    private val arrow = Path().apply {
        moveTo(0f, 0f); lineTo(0f, 22f); lineTo(6f, 17f); lineTo(10f, 27f)
        lineTo(14f, 25f); lineTo(10f, 16f); lineTo(18f, 16f); close()
    }

    override fun onDraw(c: Canvas) {
        c.save()
        c.translate(cx, cy)
        c.scale(s, s)
        c.drawPath(arrow, fill)
        c.drawPath(arrow, edge)
        c.restore()
    }
}

class CursorService : AccessibilityService() {

    private lateinit var wm: WindowManager
    private var view: CursorView? = null
    private val handler = Handler(Looper.getMainLooper())
    private var sock: DatagramSocket? = null
    @Volatile private var running = false
    private var x = 0f
    private var y = 0f
    private var w = 1920
    private var h = 1080
    private var nsd: NsdManager? = null
    private var reg: NsdManager.RegistrationListener? = null
    private val hide = Runnable { view?.visibility = View.INVISIBLE }

    override fun onServiceConnected() {
        super.onServiceConnected()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val dm = resources.displayMetrics
        w = dm.widthPixels
        h = dm.heightPixels
        x = w / 2f
        y = h / 2f
        Pin.get(this)
        addCursor()
        startServer()
        registerNsd()
    }

    private fun addCursor() {
        val v = CursorView(this)
        v.cx = x
        v.cy = y
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        wm.addView(v, lp)
        view = v
    }

    private fun startServer() {
        running = true
        Thread {
            try {
                val s = DatagramSocket(PORT)
                sock = s
                val buf = ByteArray(1024)
                while (running) {
                    val p = DatagramPacket(buf, buf.size)
                    s.receive(p)
                    handlePacket(s, p)
                }
            } catch (_: Exception) {
            }
        }.start()
    }

    private fun reply(s: DatagramSocket, p: DatagramPacket, text: String) {
        try {
            val b = text.toByteArray()
            s.send(DatagramPacket(b, b.size, p.address, p.port))
        } catch (_: Exception) {
        }
    }

    private fun handlePacket(s: DatagramSocket, p: DatagramPacket) {
        val msg = String(p.data, 0, p.length).trim()
        val i = msg.indexOf(' ')
        if (i < 0) return
        val pin = msg.substring(0, i)
        val cmd = msg.substring(i + 1)
        if (pin != Pin.get(this)) {
            if (cmd == "P") reply(s, p, "BADPIN")
            return
        }
        if (cmd == "P") {
            reply(s, p, "OK")
            handler.post { showCursor() }
            return
        }
        handler.post { run(cmd) }
    }

    private fun showCursor() {
        view?.visibility = View.VISIBLE
        handler.removeCallbacks(hide)
        handler.postDelayed(hide, 6000)
    }

    private fun redraw() {
        view?.let { it.cx = x; it.cy = y; it.invalidate() }
        showCursor()
    }

    private fun run(cmd: String) {
        val parts = cmd.split(' ', limit = 3)
        when (parts[0]) {
            "M" -> {
                val dx = parts.getOrNull(1)?.toIntOrNull() ?: return
                val dy = parts.getOrNull(2)?.toIntOrNull() ?: return
                x = (x + dx).coerceIn(0f, (w - 1).toFloat())
                y = (y + dy).coerceIn(0f, (h - 1).toFloat())
                redraw()
            }
            "C" -> { tap(60); redraw() }
            "L" -> { tap(700); redraw() }
            "Z" -> { x = w / 2f; y = h / 2f; redraw() }
            "S" -> { scroll(parts.getOrNull(1) == "d"); redraw() }
            "B" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "H" -> performGlobalAction(GLOBAL_ACTION_HOME)
            "T" -> typeText(cmd.substring(2))
            "X" -> backspace()
        }
    }

    private fun gesture(path: Path, duration: Long) {
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()
        dispatchGesture(g, null, null)
    }

    private fun tap(duration: Long) {
        val path = Path()
        path.moveTo(x, y)
        gesture(path, duration)
    }

    private fun scroll(down: Boolean) {
        val d = h / 4f
        val a = (y + d).coerceIn(0f, (h - 1).toFloat())
        val b = (y - d).coerceIn(0f, (h - 1).toFloat())
        val path = Path()
        if (down) { path.moveTo(x, a); path.lineTo(x, b) } else { path.moveTo(x, b); path.lineTo(x, a) }
        gesture(path, 300)
    }

    private fun focusedInput(): AccessibilityNodeInfo? =
        findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)

    private fun setText(node: AccessibilityNodeInfo, text: String) {
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun typeText(s: String) {
        val node = focusedInput() ?: return
        val base = if (node.isShowingHintText) "" else (node.text?.toString() ?: "")
        setText(node, base + s)
    }

    private fun backspace() {
        val node = focusedInput() ?: return
        val base = if (node.isShowingHintText) "" else (node.text?.toString() ?: "")
        if (base.isNotEmpty()) setText(node, base.dropLast(1))
    }

    private fun registerNsd() {
        try {
            val info = NsdServiceInfo().apply {
                serviceName = "Gyro Cursor TV"
                serviceType = "_gyrocursor._udp"
                port = PORT
            }
            val l = object : NsdManager.RegistrationListener {
                override fun onRegistrationFailed(i: NsdServiceInfo?, c: Int) {}
                override fun onUnregistrationFailed(i: NsdServiceInfo?, c: Int) {}
                override fun onServiceRegistered(i: NsdServiceInfo?) {}
                override fun onServiceUnregistered(i: NsdServiceInfo?) {}
            }
            val m = getSystemService(NSD_SERVICE) as NsdManager
            m.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
            nsd = m
            reg = l
        } catch (_: Exception) {
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        running = false
        try { sock?.close() } catch (_: Exception) {}
        try { reg?.let { nsd?.unregisterService(it) } } catch (_: Exception) {}
        try { view?.let { wm.removeView(it) } } catch (_: Exception) {}
        super.onDestroy()
    }
}
