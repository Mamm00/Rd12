package com.boost12.rd12.data.adb.crypto

import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Arrays

/**
 * Pure-Kotlin implementation of BoringSSL's SPAKE2 over Edwards25519 (`spake25519.cc`),
 * byte-for-byte compatible with Android 11+ `adbd` Wireless Debugging (`pairing_auth.cpp`).
 */
class Spake25519(
    private val role: Role,
    private val myName: ByteArray = if (role == Role.ALICE) CLIENT_NAME else SERVER_NAME,
    private val theirName: ByteArray = if (role == Role.ALICE) SERVER_NAME else CLIENT_NAME,
    private val secureRandom: SecureRandom = SecureRandom()
) {
    enum class Role { ALICE, BOB }

    private data class EdPoint(val x: BigInteger, val y: BigInteger)

    private var privateScalar: BigInteger? = null
    private var passwordScalar: BigInteger? = null
    private var passwordHash: ByteArray? = null
    private var myMsg: ByteArray? = null
    private var destroyed: Boolean = false

    fun generateMessage(password: ByteArray): ByteArray {
        check(!destroyed) { "Spake25519 context was destroyed" }
        check(myMsg == null) { "SPAKE2 message already generated" }

        val random64 = ByteArray(64)
        secureRandom.nextBytes(random64)
        // x25519_sc_reduce(private_tmp) followed by left_shift_3 (multiply by cofactor 8)
        val priv = bytesToUnsignedLittleEndian(random64).mod(L).shiftLeft(3)
        privateScalar = priv

        val pPoint = scalarMult(BASE_POINT, priv)

        val sha512 = MessageDigest.getInstance("SHA-512")
        val pwHash = sha512.digest(password)
        passwordHash = pwHash

        var pwScalar = bytesToUnsignedLittleEndian(pwHash).mod(L)
        // BoringSSL unilateral cofactor-clearing adjustment so (pwScalar & 7) == 0
        if (pwScalar.testBit(0)) pwScalar = pwScalar.add(L)
        if (pwScalar.testBit(1)) pwScalar = pwScalar.add(L.shiftLeft(1))
        if (pwScalar.testBit(2)) pwScalar = pwScalar.add(L.shiftLeft(2))
        passwordScalar = pwScalar

        val maskGenerator = if (role == Role.ALICE) POINT_M else POINT_N
        val mask = scalarMult(maskGenerator, pwScalar)
        val pStar = pointAdd(pPoint, mask)
        val encoded = encodePoint(pStar)
        myMsg = encoded
        return encoded.copyOf()
    }

    fun processMessage(theirMsg: ByteArray): ByteArray? {
        if (destroyed || theirMsg.size != POINT_BYTES) return null
        val localMsg = myMsg ?: return null
        val priv = privateScalar ?: return null
        val pwScalar = passwordScalar ?: return null
        val pwHash = passwordHash ?: return null

        val qStar = decodePoint(theirMsg) ?: return null
        val peerGenerator = if (role == Role.ALICE) POINT_N else POINT_M
        val peerMask = scalarMult(peerGenerator, pwScalar)
        val unmaskedPeer = pointSub(qStar, peerMask)
        val dhShared = scalarMult(unmaskedPeer, priv)
        val dhSharedBytes = encodePoint(dhShared)

        val transcript = MessageDigest.getInstance("SHA-512")
        if (role == Role.ALICE) {
            updateWithLengthPrefix(transcript, myName)
            updateWithLengthPrefix(transcript, theirName)
            updateWithLengthPrefix(transcript, localMsg)
            updateWithLengthPrefix(transcript, theirMsg)
        } else {
            updateWithLengthPrefix(transcript, theirName)
            updateWithLengthPrefix(transcript, myName)
            updateWithLengthPrefix(transcript, theirMsg)
            updateWithLengthPrefix(transcript, localMsg)
        }
        updateWithLengthPrefix(transcript, dhSharedBytes)
        updateWithLengthPrefix(transcript, pwHash)

        return transcript.digest()
    }

    fun destroy() {
        destroyed = true
        privateScalar = null
        passwordScalar = null
        passwordHash?.let { Arrays.fill(it, 0.toByte()) }
        passwordHash = null
        myMsg?.let { Arrays.fill(it, 0.toByte()) }
        myMsg = null
    }

    companion object {
        const val POINT_BYTES = 32

        val CLIENT_NAME: ByteArray = "adb pair client\u0000".toByteArray(Charsets.UTF_8)
        val SERVER_NAME: ByteArray = "adb pair server\u0000".toByteArray(Charsets.UTF_8)

        // Field prime p = 2^255 - 19
        private val P: BigInteger = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))

        // Prime subgroup order L = 2^252 + 27742317777372353535851937790883648493
        private val L: BigInteger = BigInteger.ONE.shiftLeft(252)
            .add(BigInteger("27742317777372353535851937790883648493"))

        // Edwards25519 curve constant d = -121665 / 121666 mod p
        private val D: BigInteger = BigInteger.valueOf(-121665)
            .multiply(BigInteger.valueOf(121666).modInverse(P))
            .mod(P)

        // sqrt(-1) mod p = 2^((p - 1) / 4) mod p
        private val SQRT_M1: BigInteger = BigInteger.valueOf(2)
            .modPow(P.subtract(BigInteger.ONE).shiftRight(2), P)

        private val EXP_P_PLUS_3_DIV_8: BigInteger = P.add(BigInteger.valueOf(3)).shiftRight(3)

        private val IDENTITY = EdPoint(BigInteger.ZERO, BigInteger.ONE)

        private val BASE_POINT: EdPoint = run {
            val by = BigInteger.valueOf(4).multiply(BigInteger.valueOf(5).modInverse(P)).mod(P)
            val bx = recoverX(by, signBit = 0) ?: error("Failed to initialize Ed25519 base point")
            EdPoint(bx, by)
        }

        // BoringSSL spake25519.cc canonical M and N points
        private val POINT_M = EdPoint(
            BigInteger("31406539342727633121250288103050113562375374900226415211311216773867585644232"),
            BigInteger("21177308356423958466833845032658859666296341766942662650232962324899758529114")
        )

        private val POINT_N = EdPoint(
            BigInteger("49918732221787544735331783592030787422991506689877079631459872391322455579424"),
            BigInteger("54629554431565467720832445949441049581317094546788069926228343916274969994000")
        )

        private fun pointAdd(p1: EdPoint, p2: EdPoint): EdPoint {
            val x1x2 = p1.x.multiply(p2.x).mod(P)
            val y1y2 = p1.y.multiply(p2.y).mod(P)
            val dx1x2y1y2 = D.multiply(x1x2).mod(P).multiply(y1y2).mod(P)

            val numX = p1.x.multiply(p2.y).add(p2.x.multiply(p1.y)).mod(P)
            val denX = BigInteger.ONE.add(dx1x2y1y2).mod(P).modInverse(P)

            val numY = y1y2.add(x1x2).mod(P)
            val denY = BigInteger.ONE.subtract(dx1x2y1y2).mod(P).modInverse(P)

            return EdPoint(
                x = numX.multiply(denX).mod(P),
                y = numY.multiply(denY).mod(P)
            )
        }

        private fun pointSub(p1: EdPoint, p2: EdPoint): EdPoint {
            val negP2 = EdPoint(P.subtract(p2.x).mod(P), p2.y)
            return pointAdd(p1, negP2)
        }

        private fun scalarMult(point: EdPoint, scalar: BigInteger): EdPoint {
            var result = IDENTITY
            var addend = point
            var k = scalar
            while (k > BigInteger.ZERO) {
                if (k.testBit(0)) {
                    result = pointAdd(result, addend)
                }
                addend = pointAdd(addend, addend)
                k = k.shiftRight(1)
            }
            return result
        }

        private fun encodePoint(point: EdPoint): ByteArray {
            val out = bigIntToFixedLittleEndian(point.y, POINT_BYTES)
            if (point.x.testBit(0)) {
                out[31] = (out[31].toInt() or 0x80).toByte()
            }
            return out
        }

        private fun decodePoint(encoded: ByteArray): EdPoint? {
            if (encoded.size != POINT_BYTES) return null
            val signBit = (encoded[31].toInt() ushr 7) and 1
            val yBytes = encoded.copyOf()
            yBytes[31] = (yBytes[31].toInt() and 0x7F).toByte()
            val y = bytesToUnsignedLittleEndian(yBytes)
            if (y >= P) return null
            val x = recoverX(y, signBit) ?: return null
            return EdPoint(x, y)
        }

        private fun recoverX(y: BigInteger, signBit: Int): BigInteger? {
            val y2 = y.multiply(y).mod(P)
            val u = y2.subtract(BigInteger.ONE).mod(P)
            val v = D.multiply(y2).add(BigInteger.ONE).mod(P)
            val xx = u.multiply(v.modInverse(P)).mod(P)

            var x = xx.modPow(EXP_P_PLUS_3_DIV_8, P)
            if (x.multiply(x).subtract(xx).mod(P) != BigInteger.ZERO) {
                x = x.multiply(SQRT_M1).mod(P)
            }
            if (x.multiply(x).subtract(xx).mod(P) != BigInteger.ZERO) {
                return null
            }
            if (x == BigInteger.ZERO && signBit == 1) {
                return null
            }
            if ((if (x.testBit(0)) 1 else 0) != signBit) {
                x = P.subtract(x)
            }
            return x
        }

        private fun updateWithLengthPrefix(digest: MessageDigest, data: ByteArray) {
            val lenBuf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                .putLong(data.size.toLong())
                .array()
            digest.update(lenBuf)
            digest.update(data)
        }

        private fun bytesToUnsignedLittleEndian(leBytes: ByteArray): BigInteger {
            val be = ByteArray(leBytes.size)
            for (i in leBytes.indices) {
                be[i] = leBytes[leBytes.size - 1 - i]
            }
            return BigInteger(1, be)
        }

        private fun bigIntToFixedLittleEndian(value: BigInteger, length: Int): ByteArray {
            val out = ByteArray(length)
            val be = value.toByteArray()
            var outIdx = 0
            for (i in be.indices.reversed()) {
                if (outIdx >= length) break
                out[outIdx++] = be[i]
            }
            return out
        }
    }
}
