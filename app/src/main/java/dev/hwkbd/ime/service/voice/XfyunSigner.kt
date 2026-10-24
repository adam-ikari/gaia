package dev.hwkbd.ime.service.voice

import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** 讯飞 WebSocket 的 HMAC 鉴权;抽成纯函数方便单测对照官方示例。 */
object XfyunSigner {

    const val HOST = "iat-api.xfyun.cn"
    private const val PATH = "/v2/iat"

    fun rfc1123(date: Date = Date()): String =
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("GMT")
        }.format(date)

    fun signature(apiSecret: String, date: String): String {
        val origin = "host: $HOST\ndate: $date\nGET $PATH HTTP/1.1"
        val mac = Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(apiSecret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        }
        return Base64.getEncoder().encodeToString(mac.doFinal(origin.toByteArray(StandardCharsets.UTF_8)))
    }

    fun authorization(apiKey: String, apiSecret: String, date: String): String {
        val origin = "api_key=\"$apiKey\", algorithm=\"hmac-sha256\", " +
            "headers=\"host date request-line\", signature=\"${signature(apiSecret, date)}\""
        return Base64.getEncoder().encodeToString(origin.toByteArray(StandardCharsets.UTF_8))
    }
}
