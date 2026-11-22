package com.gamestream

import android.content.Context
import android.net.wifi.WifiManager
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException

/**
 * Ищет ПК с программой GameStream PC — в Wi‑Fi сети или через USB-кабель (режим «USB-модем»).
 * Программа на ПК раз в секунду рассылает маяк "GAMESTREAM_PC|<имя>|<порт>" на UDP 47800.
 * Если телефон подключён к ПК кабелем, предпочитаем адрес ПК в USB-сети: так быстрее и стабильнее.
 */
object PcFinder {
    const val DISCOVERY_PORT = 47800
    private const val MAGIC = "GAMESTREAM_PC"

    data class Pc(val name: String, val ip: String, val port: Int, val usb: Boolean)

    /** Префиксы (первые 3 числа IP) сетей, созданных USB-модемом телефона. */
    fun usbPrefixes(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && listOf("rndis", "usb", "ncm").any { p -> it.name.startsWith(p) } }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .mapNotNull { it.hostAddress?.substringBeforeLast('.') }
    }.getOrDefault(emptyList())

    fun isUsbIp(ip: String) = ip.substringBeforeLast('.') in usbPrefixes()

    /** Блокирующий поиск, вызывать не из главного потока. */
    fun find(context: Context, timeoutMs: Int = 4000): Pc? {
        val usb = usbPrefixes()
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        // Без этого многие телефоны выкидывают широковещательные пакеты
        val lock = wifi.createMulticastLock("gamestream-discovery").apply {
            setReferenceCounted(false)
            acquire()
        }
        val socket = DatagramSocket(null).apply {
            reuseAddress = true
            broadcast = true
            soTimeout = 500
            bind(InetSocketAddress(DISCOVERY_PORT))
        }
        var fallback: Pc? = null
        try {
            val buf = ByteArray(512)
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                val packet = DatagramPacket(buf, buf.size)
                try {
                    socket.receive(packet)
                } catch (e: SocketTimeoutException) {
                    // Wi‑Fi адрес уже есть, а по USB ПК не ответил за 2,5 с — берём Wi‑Fi
                    if (fallback != null && (usb.isEmpty() || System.currentTimeMillis() > deadline - 1500)) return fallback
                    continue
                }
                val parts = String(packet.data, 0, packet.length, Charsets.UTF_8).split("|")
                if (parts.size < 3 || parts[0] != MAGIC) continue
                val port = parts[2].toIntOrNull() ?: continue
                val ip = packet.address.hostAddress ?: continue
                val viaUsb = ip.substringBeforeLast('.') in usb
                val pc = Pc(parts[1], ip, port, viaUsb)
                if (viaUsb || usb.isEmpty()) return pc
                if (fallback == null) fallback = pc
            }
            return fallback
        } finally {
            socket.close()
            lock.release()
        }
    }
}
