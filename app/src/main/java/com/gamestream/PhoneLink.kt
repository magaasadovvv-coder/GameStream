package com.gamestream

import android.os.Build
import android.os.Handler
import android.os.Looper
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import kotlin.concurrent.thread

/**
 * Связь с программой GameStream PC в режиме «На ПК».
 *  • раз в 1,5 с сообщает ПК «я стримлю» (ПК по этому сигналу открывает OBS и запускает синхронизацию);
 *  • принимает команду «вспышка» для замера задержки.
 */
class PhoneLink(pcIp: String, private val onFlash: () -> Unit) {

    companion object {
        const val COMMAND_PORT = 47801
        const val PC_PORT = 47802
    }

    @Volatile var state = "start"
    @Volatile private var running = true
    private val main = Handler(Looper.getMainLooper())
    private val pc: InetAddress? = runCatching { InetAddress.getByName(pcIp) }.getOrNull()
    private var socket: DatagramSocket? = null
    private val model = (Build.MODEL ?: "Android").replace("|", " ").take(40)

    init {
        thread(name = "phone-heartbeat", isDaemon = true) {
            while (running) {
                send(state)
                Thread.sleep(1500)
            }
        }
        thread(name = "phone-commands", isDaemon = true) { listen() }
    }

    private fun send(st: String) {
        val target = pc ?: return
        runCatching {
            DatagramSocket().use { s ->
                val data = "GAMESTREAM_PHONE|$model|$st".toByteArray()
                s.send(DatagramPacket(data, data.size, target, PC_PORT))
            }
        }
    }

    private fun listen() {
        val s = runCatching {
            DatagramSocket(null).apply {
                reuseAddress = true
                soTimeout = 1000
                bind(InetSocketAddress(COMMAND_PORT))
            }
        }.getOrNull() ?: return
        socket = s
        val buf = ByteArray(256)
        while (running) {
            val packet = DatagramPacket(buf, buf.size)
            try {
                s.receive(packet)
            } catch (e: SocketTimeoutException) {
                continue
            } catch (e: Exception) {
                break
            }
            val msg = String(packet.data, 0, packet.length)
            // Команды принимаем только от своего ПК
            if (msg.startsWith("GS_FLASH") && (pc == null || packet.address == pc)) {
                main.post(onFlash)
            }
        }
        runCatching { s.close() }
    }

    fun stop() {
        running = false
        thread(isDaemon = true) { repeat(2) { send("stop") } }
        runCatching { socket?.close() }
    }
}
