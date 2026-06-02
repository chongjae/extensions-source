package eu.kanade.tachiyomi.extension.ko.kakaowebtoon

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Decrypts AES-CBC encrypted images from Kakao Webtoon.
 *
 * The extension appends `#key=HEX&iv=HEX` to the image URL after computing the keys.
 * This interceptor strips the fragment and uses the key/iv to decrypt the response.
 *
 * Kakao Webtoon two-level decryption:
 *   masterKey = SHA-256((userId || episodeId) + episodeId + timestamp)
 *   masterIV  = SHA-256(nonce + timestamp)[0..15]
 *   imageKey  = AES-CBC-decrypt(base64(aid), masterKey, masterIV)
 *   imageIV   = AES-CBC-decrypt(base64(zid), masterKey, masterIV)
 *   image     = AES-CBC-decrypt(encryptedBytes, imageKey, imageIV)
 *
 * The keys are pre-computed in pageListParse and attached to the URL as hex fragments.
 */
object ImageInterceptor : Interceptor {

    private const val KEY_PARAM = "key"
    private const val IV_PARAM = "iv"

    private val AES: Cipher get() = Cipher.getInstance("AES/CBC/PKCS5Padding")

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url
        val fragment = url.fragment ?: return chain.proceed(request)

        // Parse key and iv from fragment: "key=HEX&iv=HEX"
        val params = fragment.split("&").associate { part ->
            val (k, v) = part.split("=", limit = 2)
            k to v
        }
        val keyHex = params[KEY_PARAM] ?: return chain.proceed(request)
        val ivHex = params[IV_PARAM] ?: return chain.proceed(request)

        // Make request without fragment
        val cleanRequest = request.newBuilder()
            .url(url.newBuilder().fragment(null).build())
            .build()

        val response = chain.proceed(cleanRequest)

        val contentType = response.body.contentType()
        val imageBytes = response.body.bytes()
        val decrypted = aesCbcDecrypt(imageBytes, keyHex.hexToBytes(), ivHex.hexToBytes())
            ?: return response.newBuilder()
                .body(imageBytes.toResponseBody(contentType))
                .build()

        // Always WebP since we request type=AES_CBC_WEBP in pageListRequest
        return response.newBuilder()
            .body(decrypted.toResponseBody("image/webp".toMediaType()))
            .build()
    }

    private fun aesCbcDecrypt(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray? = runCatching {
        AES.apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv)) }
            .doFinal(data)
    }.getOrNull()

    // ─── Key derivation helpers ───────────────────────────────────────────────

    fun deriveImageKeys(
        userId: String?,
        episodeId: Long,
        nonce: String,
        timestamp: String,
        aid: String,
        zid: String,
    ): Pair<ByteArray, ByteArray>? {
        val masterInput = "${userId ?: episodeId}${episodeId}$timestamp"
        val masterKey = sha256(masterInput)

        val ivInput = "${nonce}$timestamp"
        val masterIV = sha256(ivInput).copyOf(16)

        val encAid = android.util.Base64.decode(aid, android.util.Base64.DEFAULT)
        val encZid = android.util.Base64.decode(zid, android.util.Base64.DEFAULT)

        val imageKey = aesCbcDecrypt(encAid, masterKey, masterIV) ?: return null
        val imageIV = aesCbcDecrypt(encZid, masterKey, masterIV) ?: return null

        return Pair(imageKey, imageIV)
    }

    private fun sha256(input: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.ISO_8859_1))

    private fun String.hexToBytes(): ByteArray {
        check(length % 2 == 0) { "Odd-length hex string" }
        return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
