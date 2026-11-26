package com.boost12.rd12.data.adb.crypto

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Implements AOSP `PairingAuthCtx` (`adb/pairing_auth/pairing_auth.cpp` & `aes_128_gcm.cpp`):
 * SPAKE25519 key agreement -> HKDF-SHA256 ("adb pairing_auth aes-128-gcm key") -> AES-128-GCM.
 */
class PairingAuthCipher private constructor(
    private val spake: Spake25519,
    passwordMaterial: ByteArray
) {
    val publicMessage: ByteArray = spake.generateMessage(passwordMaterial)

    private val aesKeyBytes = ByteArray(HKDF_KEY_LEN)
    private var cipherInitialized = false
    private var encryptSequence: Long = 0L
    private var decryptSequence: Long = 0L
    private var destroyed = false

    fun initCipher(peerSpakeMessage: ByteArray): Boolean {
        if (destroyed) return false
        val sharedKey = spake.processMessage(peerSpakeMessage) ?: return false
        return try {
            val derived = hkdfSha256(
                ikm = sharedKey,
                info = HKDF_INFO,
                outputLength = HKDF_KEY_LEN
            )
            System.arraycopy(derived, 0, aesKeyBytes, 0, HKDF_KEY_LEN)
            Arrays.fill(sharedKey, 0.toByte())
            Arrays.fill(derived, 0.toByte())
            cipherInitialized = true
            true
        } catch (_: Exception) {
            false
        }
    }

    fun encrypt(plaintext: ByteArray): ByteArray? {
        if (destroyed || !cipherInitialized) return null
        val iv = buildLittleEndianIv(encryptSequence++)
        return runAesGcm(Cipher.ENCRYPT_MODE, plaintext, iv)
    }

    fun decrypt(ciphertext: ByteArray): ByteArray? {
        if (destroyed || !cipherInitialized) return null
        val iv = buildLittleEndianIv(decryptSequence++)
        return runAesGcm(Cipher.DECRYPT_MODE, ciphertext, iv)
    }

    fun destroy() {
        destroyed = true
        cipherInitialized = false
        Arrays.fill(aesKeyBytes, 0.toByte())
        spake.destroy()
    }

    private fun buildLittleEndianIv(counter: Long): ByteArray {
        return ByteBuffer.allocate(GCM_IV_LEN)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putLong(counter)
            .array()
    }

    private fun runAesGcm(mode: Int, input: ByteArray, iv: ByteArray): ByteArray? {
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val keySpec = SecretKeySpec(aesKeyBytes, "AES")
            val gcmSpec = GCMParameterSpec(GCM_TAG_BITS, iv)
            cipher.init(mode, keySpec, gcmSpec)
            cipher.doFinal(input)
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private val HKDF_INFO = "adb pairing_auth aes-128-gcm key".toByteArray(Charsets.UTF_8)
        private const val HKDF_KEY_LEN = 16
        private const val GCM_IV_LEN = 12
        private const val GCM_TAG_BITS = 128

        fun createClient(passwordMaterial: ByteArray): PairingAuthCipher {
            return PairingAuthCipher(Spake25519(Spake25519.Role.ALICE), passwordMaterial)
        }

        fun createServerForTest(passwordMaterial: ByteArray): PairingAuthCipher {
            return PairingAuthCipher(Spake25519(Spake25519.Role.BOB), passwordMaterial)
        }

        /**
         * RFC 5869 HKDF-SHA256 with empty/zero salt (matching BoringSSL `HKDF` when salt == null).
         */
        internal fun hkdfSha256(ikm: ByteArray, info: ByteArray, outputLength: Int): ByteArray {
            val zeroSalt = ByteArray(32)
            val extractMac = Mac.getInstance("HmacSHA256").apply {
                init(SecretKeySpec(zeroSalt, "HmacSHA256"))
            }
            val prk = extractMac.doFinal(ikm)

            val expandMac = Mac.getInstance("HmacSHA256").apply {
                init(SecretKeySpec(prk, "HmacSHA256"))
            }
            val result = ByteArray(outputLength)
            var t = ByteArray(0)
            var offset = 0
            var blockIndex = 1
            while (offset < outputLength) {
                expandMac.reset()
                expandMac.update(t)
                expandMac.update(info)
                expandMac.update(blockIndex.toByte())
                t = expandMac.doFinal()
                val copyLen = minOf(t.size, outputLength - offset)
                System.arraycopy(t, 0, result, offset, copyLen)
                offset += copyLen
                blockIndex++
            }
            Arrays.fill(prk, 0.toByte())
            return result
        }
    }
}
