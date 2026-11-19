package com.nononsenseapps.feeder.sync2

import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Keys for Feeder's own sync, derived from the sync code and the HMAC half of the secret key the
 * devices already share. Must match web/src/lib/sync2.ts (deriveKeys / recordId) byte for byte.
 */
class Sync2Keys(
    syncCode: String,
    hmacKey: ByteArray,
) {
    val account: String
    val auth: String
    private val idKey: ByteArray

    init {
        val code = syncCode.lowercase()
        account = b64url(hmac(hmacKey, "feeder-sync2 account\n$code"))
        auth = b64url(hmac(hmacKey, "feeder-sync2 auth\n$code"))
        idKey = hmac(hmacKey, "feeder-sync2 id")
    }

    /** The server-side id of a record: an HMAC, so the server never sees feed URLs or guids. */
    fun recordId(
        kind: String,
        key: String,
    ): String = b64url(hmac(idKey, "$kind\n$key"))

    companion object {
        fun hmac(
            key: ByteArray,
            text: String,
        ): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key, "HmacSHA256"))
            return mac.doFinal(text.toByteArray(Charsets.UTF_8))
        }

        fun b64url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
