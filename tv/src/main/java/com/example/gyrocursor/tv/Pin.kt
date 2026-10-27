package com.example.gyrocursor.tv

import android.content.Context
import java.security.SecureRandom

object Pin {
    private fun prefs(c: Context) = c.getSharedPreferences("cursor", Context.MODE_PRIVATE)

    fun get(c: Context): String {
        var p = prefs(c).getString("pin", null)
        if (p == null) {
            p = (1000 + SecureRandom().nextInt(9000)).toString()
            prefs(c).edit().putString("pin", p).apply()
        }
        return p
    }

    fun renew(c: Context): String {
        prefs(c).edit().remove("pin").apply()
        return get(c)
    }
}
