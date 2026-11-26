package com.boost12.rd12.data.adb.crypto

import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import javax.crypto.Cipher

/**
 * Encodes/decodes AOSP's 524-byte `RSAPublicKey` binary struct (`libcrypto_utils/android_pubkey.cpp`)
 * used in Wireless Debugging `PeerInfo` (`ADB_RSA_PUB_KEY`) and legacy `A_AUTH` frames.
 */
object AndroidPubkeyCodec {
    const val MODULUS_BYTES = 2048 / 8
    const val MODULUS_WORDS = MODULUS_BYTES / 4
    const val ENCODED_STRUCT_BYTES = 4 + 4 + MODULUS_BYTES + MODULUS_BYTES + 4 // 524 bytes

    // PKCS#1 v1.5 SHA-1 DigestInfo prefix for 2048-bit RSA raw signature (`adb_auth_sign`)
    private val SHA1_PKCS1_PADDING: ByteArray = run {
        val header = byteArrayOf(0x00, 0x01)
        val ffFill = ByteArray(218) { 0xFF.toByte() }
        val separatorAndAsn1 = byteArrayOf(
            0x00,
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2B, 0x0E,
            0x03, 0x02, 0x1A, 0x05, 0x00, 0x04, 0x14
        )
        header + ffFill + separatorAndAsn1
    }

    fun encodeStruct(publicKey: RSAPublicKey): ByteArray {
        val modulus = publicKey.modulus
        val modulusLe = bigIntToLittleEndianPadded(modulus, MODULUS_BYTES)
            ?: throw IllegalArgumentException("RSA modulus must be 2048-bit")

        val r32 = BigInteger.ONE.shiftLeft(32)
        val n0 = modulus.mod(r32)
        val n0Inv = r32.subtract(n0.modInverse(r32))

        val r = BigInteger.ONE.shiftLeft(MODULUS_BYTES * 8)
        val rr = r.modPow(BigInteger.valueOf(2), modulus)
        val rrLe = bigIntToLittleEndianPadded(rr, MODULUS_BYTES)
            ?: throw IllegalArgumentException("Failed to encode Montgomery R^2")

        return ByteBuffer.allocate(ENCODED_STRUCT_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(MODULUS_WORDS)
            .putInt(n0Inv.toInt())
            .put(modulusLe)
            .put(rrLe)
            .putInt(publicKey.publicExponent.toInt())
            .array()
    }

    fun encodeWithName(publicKey: RSAPublicKey, deviceLabel: String = "Boost12@Redmi12"): ByteArray {
        val base64Pubkey = Base64.getEncoder().encode(encodeStruct(publicKey))
        val suffix = " $deviceLabel\u0000".toByteArray(Charsets.UTF_8)
        return base64Pubkey + suffix
    }

    fun decodeStruct(structBytes: ByteArray): RSAPublicKey {
        require(structBytes.size >= ENCODED_STRUCT_BYTES) { "Invalid AndroidPubkey size" }
        val buf = ByteBuffer.wrap(structBytes).order(ByteOrder.LITTLE_ENDIAN)
        val words = buf.int
        require(words == MODULUS_WORDS) { "Unexpected modulus word count: $words" }
        buf.int // skip n0inv

        val modulusLe = ByteArray(MODULUS_BYTES)
        buf.get(modulusLe)
        val modulusBe = modulusLe.reversedArray()
        val modulus = BigInteger(1, modulusBe)

        val rrSkip = ByteArray(MODULUS_BYTES)
        buf.get(rrSkip)
        val exponent = BigInteger.valueOf(buf.int.toLong() and 0xFFFFFFFFL)

        return KeyFactory.getInstance("RSA")
            .generatePublic(RSAPublicKeySpec(modulus, exponent)) as RSAPublicKey
    }

    fun signAdbAuthToken(privateKey: PrivateKey, token20Bytes: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("RSA/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, privateKey)
        cipher.update(SHA1_PKCS1_PADDING)
        return cipher.doFinal(token20Bytes)
    }

    private fun bigIntToLittleEndianPadded(value: BigInteger, length: Int): ByteArray? {
        val be = value.toByteArray()
        val unsignedBe = if (be.size > 1 && be[0] == 0.toByte()) be.copyOfRange(1, be.size) else be
        if (unsignedBe.size > length) return null
        val out = ByteArray(length)
        for (i in unsignedBe.indices) {
            out[i] = unsignedBe[unsignedBe.size - 1 - i]
        }
        return out
    }
}
