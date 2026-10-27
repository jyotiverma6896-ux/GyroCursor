package com.example.gyrocursor.phone

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.Executors

/** Sends "PIN command args" UDP packets to the TV app. */
class Sender {
    companion object { const val PORT = 9876 }

    private val exec = Executors.newSingleThreadExecutor()
    private var socket: DatagramSocket? = null
    @Volatile var host: String? = null
    @Volatile var pin: String = ""

    init {
        exec.execute {
            try {
                socket = DatagramSocket().apply { soTimeout = 1500 }
            } catch (_: Exception) {
            }
        }
    }

    fun send(cmd: String) {
        val h = host ?: return
        val p = pin
        exec.execute {
            try {
                val b = "$p $cmd".toByteArray()
                socket?.send(DatagramPacket(b, b.size, InetAddress.getByName(h), PORT))
            } catch (_: Exception) {
            }
        }
    }

    /** Result: "OK", "BADPIN", "TIMEOUT" or "ERR ...". */
    fun ping(onResult: (String) -> Unit) {
        val h = host
        if (h == null) { onResult("ERR IP nahi hai"); return }
        val p = pin
        exec.execute {
            val r = try {
                val b = "$p P".toByteArray()
                socket?.send(DatagramPacket(b, b.size, InetAddress.getByName(h), PORT))
                val buf = ByteArray(64)
                val pk = DatagramPacket(buf, buf.size)
                socket?.receive(pk)
                String(pk.data, 0, pk.length).trim()
            } catch (e: SocketTimeoutException) {
                "TIMEOUT"
            } catch (e: Exception) {
                "ERR ${e.message}"
            }
            onResult(r)
        }
    }

    fun close() {
        exec.execute { socket?.close() }
        exec.shutdown()
    }
}
