package com.boost12.rd12.data.adb.protocol

import com.boost12.rd12.data.adb.crypto.AdbKeyMaterial
import com.boost12.rd12.data.adb.crypto.AndroidPubkeyCodec
import com.boost12.rd12.data.adb.crypto.PairingAuthCipher
import com.boost12.rd12.domain.model.AdbErrorKind
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Arrays
import javax.net.ssl.SSLSocket

class AdbPairingException(
    val kind: AdbErrorKind,
    override val message: String,
    cause: Throwable? = null
) : IOException(message, cause)

/**
 * Executes Android 11+ Wireless Debugging SPAKE2+ pairing (`pairing_connection.cpp`):
 * 1. TLSv1.3 handshake to `_adb-tls-pairing._tcp` port
 * 2. Export 64-byte EKM (`"adb-label\u0000"`) and append to 6-digit pairing code
 * 3. Exchange 32-byte `SPAKE2_MSG` packets
 * 4. Exchange AES-128-GCM encrypted 8192-byte `PEER_INFO` containing our RSA-2048 ADB public key
 */
class AdbPairingClient(
    private val onProtocolTrace: (String) -> Unit = {}
) {
    fun pair(
        host: String,
        port: Int,
        sixDigitCode: String,
        keyMaterial: AdbKeyMaterial,
        deviceLabel: String = "Boost12@Redmi12",
        connectTimeoutMs: Int = 8_000,
        handshakeTimeoutMs: Int = 20_000
    ) {
        val normalizedCode = sixDigitCode.trim()
        if (normalizedCode.length != 6 || normalizedCode.any { !it.isDigit() }) {
            throw AdbPairingException(
                AdbErrorKind.PAIRING_CODE_REJECTED,
                "Pairing code must be a 6-digit number from Wireless Debugging."
            )
        }

        val codeBytes = normalizedCode.toByteArray(Charsets.UTF_8)
        var authCipher: PairingAuthCipher? = null
        val plainSocket = Socket()

        try {
            onProtocolTrace("Connecting TCP to pairing endpoint $host:$port")
            plainSocket.tcpNoDelay = true
            plainSocket.connect(InetSocketAddress(host, port), connectTimeoutMs)
            plainSocket.soTimeout = handshakeTimeoutMs

            val (sslContext, usingConscrypt) = SslContextFactory.createTls13Context(keyMaterial)
            val sslSocket = sslContext.socketFactory.createSocket(
                plainSocket,
                host,
                port,
                true
            ) as SSLSocket
            sslSocket.enabledProtocols = arrayOf("TLSv1.3")
            sslSocket.soTimeout = handshakeTimeoutMs

            onProtocolTrace("Starting TLSv1.3 pairing handshake (Conscrypt=$usingConscrypt)")
            sslSocket.startHandshake()

            val ekm = SslContextFactory.exportKeyingMaterial(sslSocket, usingConscrypt)
            val passwordMaterial = ByteArray(codeBytes.size + ekm.size)
            System.arraycopy(codeBytes, 0, passwordMaterial, 0, codeBytes.size)
            System.arraycopy(ekm, 0, passwordMaterial, codeBytes.size, ekm.size)
            Arrays.fill(ekm, 0.toByte())

            authCipher = PairingAuthCipher.createClient(passwordMaterial)
            Arrays.fill(passwordMaterial, 0.toByte())

            val input = DataInputStream(sslSocket.inputStream)
            val output = DataOutputStream(sslSocket.outputStream)

            // Phase 1: Exchange SPAKE2_MSG
            val clientSpakeMsg = authCipher.publicMessage
            writePacket(output, PACKET_TYPE_SPAKE2_MSG, clientSpakeMsg)
            onProtocolTrace("Sent SPAKE2_MSG (${clientSpakeMsg.size}B); awaiting adbd SPAKE2_MSG")

            val (peerMsgType, peerSpakeMsg) = readPacket(input)
            if (peerMsgType != PACKET_TYPE_SPAKE2_MSG) {
                throw AdbPairingException(
                    AdbErrorKind.PAIRING_CODE_REJECTED,
                    "Unexpected pairing packet type $peerMsgType (expected SPAKE2_MSG)."
                )
            }
            if (!authCipher.initCipher(peerSpakeMsg)) {
                throw AdbPairingException(
                    AdbErrorKind.PAIRING_CODE_REJECTED,
                    "SPAKE2+ key agreement failed — check the 6-digit pairing code."
                )
            }

            // Phase 2: Exchange encrypted 8192-byte PeerInfo
            val pubkeyPayload = AndroidPubkeyCodec.encodeWithName(
                publicKey = keyMaterial.rsaPublicKey,
                deviceLabel = deviceLabel
            )
            val peerInfoPlain = ByteBuffer.allocate(PEER_INFO_TOTAL_BYTES)
                .order(ByteOrder.BIG_ENDIAN)
                .put(PEER_INFO_TYPE_RSA_PUB_KEY)
                .put(pubkeyPayload, 0, minOf(pubkeyPayload.size, PEER_INFO_TOTAL_BYTES - 1))
                .array()

            val encryptedPeerInfo = authCipher.encrypt(peerInfoPlain)
                ?: throw AdbPairingException(
                    AdbErrorKind.UNKNOWN,
                    "Failed to encrypt ADB RSA PeerInfo."
                )

            writePacket(output, PACKET_TYPE_PEER_INFO, encryptedPeerInfo)
            onProtocolTrace("Sent encrypted PEER_INFO (${encryptedPeerInfo.size}B); awaiting adbd GUID")

            val (peerInfoType, peerEncryptedReply) = readPacket(input)
            if (peerInfoType != PACKET_TYPE_PEER_INFO) {
                throw AdbPairingException(
                    AdbErrorKind.PAIRING_CODE_REJECTED,
                    "Unexpected pairing reply packet type $peerInfoType."
                )
            }

            val decryptedReply = authCipher.decrypt(peerEncryptedReply)
                ?: throw AdbPairingException(
                    AdbErrorKind.PAIRING_CODE_REJECTED,
                    "Pairing code was rejected by Wireless Debugging. Generate a new 6-digit code."
                )

            if (decryptedReply.size != PEER_INFO_TOTAL_BYTES) {
                throw AdbPairingException(
                    AdbErrorKind.PAIRING_CODE_REJECTED,
                    "Unexpected PeerInfo size (${decryptedReply.size}B) from adbd."
                )
            }

            onProtocolTrace("SPAKE2+ pairing succeeded; RSA-2048 certificate registered with adbd")
            runCatching { sslSocket.close() }
        } catch (e: AdbPairingException) {
            throw e
        } catch (e: SocketTimeoutException) {
            throw AdbPairingException(
                AdbErrorKind.PAIRING_TIMEOUT,
                "Pairing timed out. Keep Developer Options → Wireless Debugging → 'Pair device with pairing code' open in split-screen.",
                e
            )
        } catch (e: IOException) {
            val msg = e.message.orEmpty()
            val kind = when {
                msg.contains("Connection refused", ignoreCase = true) -> AdbErrorKind.CONNECTION_REFUSED
                msg.contains("timed out", ignoreCase = true) -> AdbErrorKind.PAIRING_TIMEOUT
                msg.contains("decrypt", ignoreCase = true) ||
                    msg.contains("BAD_DECRYPT", ignoreCase = true) ||
                    msg.contains("EOF", ignoreCase = true) -> AdbErrorKind.PAIRING_CODE_REJECTED
                else -> AdbErrorKind.CONNECTION_REFUSED
            }
            val friendly = when (kind) {
                AdbErrorKind.PAIRING_CODE_REJECTED ->
                    "Pairing rejected or pairing dialog closed. Tap 'Pair device with pairing code' again for a fresh port & code."
                AdbErrorKind.CONNECTION_REFUSED ->
                    "Could not reach pairing port $host:$port. Note that the pairing port changes every time you open the pairing dialog."
                else -> "Wireless Debugging pairing failed: ${e.message ?: "I/O error"}"
            }
            throw AdbPairingException(kind, friendly, e)
        } finally {
            Arrays.fill(codeBytes, 0.toByte())
            authCipher?.destroy()
            runCatching { plainSocket.close() }
        }
    }

    private fun writePacket(output: DataOutputStream, type: Byte, payload: ByteArray) {
        val header = ByteBuffer.allocate(HEADER_SIZE)
            .order(ByteOrder.BIG_ENDIAN)
            .put(HEADER_VERSION)
            .put(type)
            .putInt(payload.size)
            .array()
        output.write(header)
        output.write(payload)
        output.flush()
    }

    private fun readPacket(input: DataInputStream): Pair<Byte, ByteArray> {
        val headerBytes = ByteArray(HEADER_SIZE)
        input.readFully(headerBytes)
        val header = ByteBuffer.wrap(headerBytes).order(ByteOrder.BIG_ENDIAN)
        val version = header.get()
        val type = header.get()
        val payloadSize = header.int

        if (version != HEADER_VERSION) {
            throw AdbPairingException(
                AdbErrorKind.UNKNOWN,
                "Unsupported Wireless Debugging pairing header version $version"
            )
        }
        if (payloadSize <= 0 || payloadSize > MAX_SAFE_PAYLOAD) {
            throw AdbPairingException(
                AdbErrorKind.UNKNOWN,
                "Invalid Wireless Debugging pairing payload size: $payloadSize"
            )
        }
        val payload = ByteArray(payloadSize)
        input.readFully(payload)
        return type to payload
    }

    companion object {
        private const val HEADER_VERSION: Byte = 1
        private const val HEADER_SIZE = 6
        private const val PACKET_TYPE_SPAKE2_MSG: Byte = 0
        private const val PACKET_TYPE_PEER_INFO: Byte = 1
        private const val PEER_INFO_TYPE_RSA_PUB_KEY: Byte = 0
        private const val PEER_INFO_TOTAL_BYTES = 1 shl 13 // 8192 bytes per AOSP PeerInfo struct
        private const val MAX_SAFE_PAYLOAD = 2 * PEER_INFO_TOTAL_BYTES
    }
}
