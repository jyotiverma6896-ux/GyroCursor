package com.example.gyrocursor.tv

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.net.Inet4Address
import java.net.NetworkInterface

class TvActivity : Activity() {

    private lateinit var info: TextView

    private fun localIp(): String = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { !it.isLoopbackAddress && it is Inet4Address }
            ?.hostAddress ?: "?"
    } catch (_: Exception) {
        "?"
    }

    private fun serviceOn(): Boolean {
        val s = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        return s?.contains("com.example.gyrocursor.tv") == true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(80, 60, 80, 60)
        }
        info = TextView(this).apply { textSize = 26f }
        root.addView(info)
        root.addView(Button(this).apply {
            text = "Accessibility settings kholo"
            setOnClickListener {
                try { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } catch (_: Exception) {}
            }
        }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        root.addView(Button(this).apply {
            text = "Naya PIN banao"
            setOnClickListener { Pin.renew(this@TvActivity); refresh() }
        }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val on = serviceOn()
        info.text = "Gyro Cursor TV\n\n" +
            "IP:  ${localIp()}\n" +
            "PIN:  ${Pin.get(this)}\n\n" +
            "Service: " + (if (on) "ON ✔" else "OFF ✘  (neeche button se Gyro Cursor ko on karo)") + "\n\n" +
            "Phone app me ye IP aur PIN daalo."
    }
}
