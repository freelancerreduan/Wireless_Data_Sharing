package com.reduan.smartinfo.security

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

object Tokens {
    private val random = SecureRandom()

    /** URL-safe token from a cryptographically secure generator. */
    fun newToken(bytes: Int = 24): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes(bytes))

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { random.nextBytes(it) }

    /** Constant-time comparison to avoid timing leaks on token checks. */
    fun matches(expected: String, actual: String?): Boolean =
        actual != null && MessageDigest.isEqual(expected.toByteArray(), actual.toByteArray())
}
