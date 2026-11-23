package com.gamestream

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import android.util.Base64
import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * Доступ по приглашению. Ключ создаёт владелец в программе GameStream PC для конкретного телефона:
 * он подписан секретным ключом владельца, поэтому подделать его нельзя,
 * а на другом телефоне (с другим кодом устройства) он не подойдёт.
 *
 * Формат ключа: "GS1-" + base64url( версия(1) | код устройства(8) | срок(4, дни с 1970, 0 = навсегда) | подпись r||s(64) ).
 */
object License {
    private const val OWNER_PUBLIC_KEY = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEcGS2NX0eezg0p/78wn+G+FH/d3ubCkXfX7sEWNx3yG0ug39NxGBjU230Qa/rz+CB95fJ5JrGBzDemael2q3vIA=="
    private const val PREFIX = "GS1-"
    private const val PAYLOAD = 13

    sealed class Check {
        data class Ok(val expiresDay: Long) : Check()
        data class Bad(val reason: String) : Check()
    }

    /** Код устройства: показываем другу, он отправляет его владельцу. */
    @SuppressLint("HardwareIds")
    fun deviceCode(ctx: Context): String {
        val raw = (Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID) ?: "0")
            .lowercase().filter { it in "0123456789abcdef" }.takeLast(16).padStart(16, '0')
        return raw.uppercase().chunked(4).joinToString("-")
    }

    private fun deviceBytes(ctx: Context): ByteArray =
        BigInteger(deviceCode(ctx).replace("-", ""), 16).toByteArray().let { b ->
            // BigInteger может добавить нулевой байт спереди или отдать меньше 8 байт
            ByteArray(8).also { out ->
                val src = if (b.size > 8) b.copyOfRange(b.size - 8, b.size) else b
                System.arraycopy(src, 0, out, 8 - src.size, src.size)
            }
        }

    fun check(ctx: Context, key: String): Check {
        val text = key.trim().replace("\\s".toRegex(), "")
        if (!text.startsWith(PREFIX)) return Check.Bad("Это не ключ GameStream")
        val data = runCatching { Base64.decode(text.removePrefix(PREFIX), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP) }
            .getOrNull() ?: return Check.Bad("Ключ повреждён — скопируй его целиком")
        if (data.size != PAYLOAD + 64 || data[0].toInt() != 1) return Check.Bad("Ключ повреждён — скопируй его целиком")

        val payload = data.copyOfRange(0, PAYLOAD)
        val sig = data.copyOfRange(PAYLOAD, PAYLOAD + 64)
        val valid = runCatching {
            val pub = KeyFactory.getInstance("EC").generatePublic(
                X509EncodedKeySpec(Base64.decode(OWNER_PUBLIC_KEY, Base64.DEFAULT))
            )
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(pub)
                update(payload)
                verify(toDer(sig))
            }
        }.getOrDefault(false)
        if (!valid) return Check.Bad("Ключ ненастоящий")

        if (!payload.copyOfRange(1, 9).contentEquals(deviceBytes(ctx))) {
            return Check.Bad("Этот ключ создан для другого телефона")
        }
        val expires = ByteBuffer.wrap(payload, 9, 4).int.toLong() and 0xffffffffL
        val today = System.currentTimeMillis() / 86_400_000L
        if (expires != 0L && today > expires) return Check.Bad("Срок ключа закончился — попроси новый")
        return Check.Ok(expires)
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("license", Context.MODE_PRIVATE)

    fun savedKey(ctx: Context): String = prefs(ctx).getString("key", "") ?: ""

    fun save(ctx: Context, key: String) = prefs(ctx).edit().putString("key", key.trim()).apply()

    fun isUnlocked(ctx: Context): Boolean = check(ctx, savedKey(ctx)) is Check.Ok

    /** Подпись r||s (по 32 байта) → формат DER, который понимает Java. */
    private fun toDer(raw: ByteArray): ByteArray {
        fun part(b: ByteArray): ByteArray {
            var i = 0
            while (i < b.size - 1 && b[i].toInt() == 0) i++
            val t = b.copyOfRange(i, b.size)
            return if (t[0].toInt() and 0x80 != 0) byteArrayOf(0) + t else t
        }
        val r = part(raw.copyOfRange(0, 32))
        val s = part(raw.copyOfRange(32, 64))
        val body = byteArrayOf(0x02, r.size.toByte()) + r + byteArrayOf(0x02, s.size.toByte()) + s
        return byteArrayOf(0x30, body.size.toByte()) + body
    }
}
