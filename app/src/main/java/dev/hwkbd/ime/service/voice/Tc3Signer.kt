package dev.hwkbd.ime.service.voice

import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneOffset
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 腾讯云 API 3.0 的 TC3-HMAC-SHA256 签名。
 *
 * 只实现本项目用到的 POST + JSON + 固定 content-type 这一种形态;参数顺序与
 * 官方文档一致,抽成纯函数是为了能在 JVM 单测里直接验证,不用起 Android。
 */
object Tc3Signer {

    private const val ALGORITHM = "TC3-HMAC-SHA256"
    private const val CONTENT_TYPE = "application/json; charset=utf-8"
    private const val SERVICE = "asr"

    fun authorization(
        secretId: String,
        secretKey: String,
        host: String,
        payload: String,
        timestampSeconds: Long,
    ): String {
        val date = Instant.ofEpochSecond(timestampSeconds).atZone(ZoneOffset.UTC).toLocalDate().toString()
        val canonicalRequest = buildString {
            append("POST\n")
            append("/\n")
            append("\n")
            append("content-type:")
            append(CONTENT_TYPE)
            append("\nhost:")
            append(host)
            append("\n\n")
            append("content-type;host\n")
            append(sha256Hex(payload))
        }
        val scope = "$date/$SERVICE/tc3_request"
        val stringToSign = "$ALGORITHM\n$timestampSeconds\n$scope\n${sha256Hex(canonicalRequest)}"
        val secretDate = hmac(("TC3$secretKey").toByteArray(StandardCharsets.UTF_8), date)
        val secretService = hmac(secretDate, SERVICE)
        val secretSigning = hmac(secretService, "tc3_request")
        val signature = hmac(secretSigning, stringToSign).toHex()
        return "$ALGORITHM Credential=$secretId/$scope, SignedHeaders=content-type;host, Signature=$signature"
    }

    fun sha256Hex(value: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .toHex()

    private fun hmac(key: ByteArray, value: String): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(value.toByteArray(StandardCharsets.UTF_8))
        }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
