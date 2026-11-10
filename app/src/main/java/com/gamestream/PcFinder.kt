package com.gamestream

import android.content.Context
import android.net.wifi.WifiManager
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

/**
 * Ищет ПК с программой GameStream PC в той же Wi‑Fi сети.
 * Программа на ПК раз в секунду рассылает маяк "GAMESTREAM_PC|<имя>|<порт>" на UDP 47800.
 */
object PcFinder {
    const val DISCOVERY_PORT = 47800
    private const val MAGIC = "GAMESTREAM_PC"

    data class Pc(val name: String, val ip: String, val port: Int)

    /** Блокирующий поиск, вызывать не из главного потока. */
    fun find(context: Context, timeoutMs: Int = 4000): Pc? {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        // Без этого многие телефоны выкидывают широковещательные пакеты
        val lock = wifi.createMulticastLock("gamestream-discovery").apply {
            setReferenceCounted(false)
            acquire()
        }
        val socket = DatagramSocket(null).apply {
            reuseAddress = true
            broadcast = true
            soTimeout = timeoutMs
            bind(InetSocketAddress(DISCOVERY_PORT))
        }
        try {
            val buf = ByteArray(512)
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                val packet = DatagramPacket(buf, buf.size)
                try {
                    socket.receive(packet)
                } catch (e: SocketTimeoutException) {
                    return null
                }
                val parts = String(packet.data, 0, packet.length, Charsets.UTF_8).split("|")
                if (parts.size >= 3 && parts[0] == MAGIC) {
                    val port = parts[2].toIntOrNull() ?: continue
                    val ip = packet.address.hostAddress ?: continue
                    return Pc(parts[1], ip, port)
                }
            }
            return null
        } finally {
            socket.close()
            lock.release()
        }
    }
}
