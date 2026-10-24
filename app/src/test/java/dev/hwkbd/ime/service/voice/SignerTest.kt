package dev.hwkbd.ime.service.voice

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 两个云端签名的固定向量。
 *
 * 期望值由独立实现(Python hashlib/hmac)按同样的规范算出,不依赖本文件的代码路径;
 * 改签名实现时这里必须跟着变,否则就是改错了。
 */
class SignerTest {

    @Test
    fun `tc3 signature matches reference`() {
        val authorization = Tc3Signer.authorization(
            secretId = "AKIDEXAMPLE",
            secretKey = "SECRETKEYEXAMPLE",
            host = "asr.tencentcloudapi.com",
            payload = """{"ProjectId":0,"EngSerViceType":"16k_zh","SourceType":1,"VoiceFormat":"wav","Data":"AAAA","DataLen":48}""",
            timestampSeconds = 1_700_000_000,
        )
        assertEquals(
            "TC3-HMAC-SHA256 Credential=AKIDEXAMPLE/2023-11-14/asr/tc3_request, " +
                "SignedHeaders=content-type;host, " +
                "Signature=e942b78d2aea0326e1a92160408cde117c336cdef73a4e4f8c01c640425dff56",
            authorization,
        )
    }

    @Test
    fun `xfyun signature and authorization match reference`() {
        val date = "Sun, 06 Jan 2019 08:41:39 GMT"
        assertEquals(
            "I3SzmN4uRvBR1jghTw7wRHcVzSlVtVIsOdFQeB6yMSA=",
            XfyunSigner.signature("test-api-secret", date),
        )
        assertEquals(
            "YXBpX2tleT0idGVzdC1hcGkta2V5IiwgYWxnb3JpdGhtPSJobWFjLXNoYTI1NiIsIGhlYWRlcnM9Imhvc3QgZGF0ZSByZXF1ZXN0LWxpbmUiLCBzaWduYXR1cmU9IkkzU3ptTjR1UnZCUjFqZ2hUdzd3UkhjVnpTbFZ0VklzT2RGUWVCNnlNU0E9Ig==",
            XfyunSigner.authorization("test-api-key", "test-api-secret", date),
        )
    }
}
