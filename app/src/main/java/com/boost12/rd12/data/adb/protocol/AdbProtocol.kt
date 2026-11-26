package com.boost12.rd12.data.adb.protocol

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Wire-level AOSP ADB transport framing (`system/core/adb/adb.h` & `transport.cpp`).
 */
object AdbProtocol {
    const val HEADER_LENGTH = 24

    const val A_SYNC = 0x434E5953
    const val A_CNXN = 0x4E584E43
    const val A_OPEN = 0x4E45504F
    const val A_OKAY = 0x59414B4F
    const val A_CLSE = 0x45534C43
    const val A_WRTE = 0x45545257
    const val A_AUTH = 0x48545541
    const val A_STLS = 0x534C5453

    const val A_VERSION_MIN = 0x01000000
    const val A_VERSION_SKIP_CHECKSUM = 0x01000001
    const val A_STLS_VERSION = 0x01000000

    const val MAX_PAYLOAD_V1 = 4 * 1024
    const val MAX_PAYLOAD_MODERN = 1024 * 1024 // 1 MiB since Android 9+
    const val HARD_MAX_PAYLOAD_GUARD = 4 * 1024 * 1024 // Defensive 4 MiB guard against corrupt frames

    const val ADB_AUTH_TOKEN = 1
    const val ADB_AUTH_SIGNATURE = 2
    const val ADB_AUTH_RSAPUBLICKEY = 3

    private val HOST_SYSTEM_IDENTITY = "host::features=shell_v2,cmd,stat_v2\u0000".toByteArray(Charsets.UTF_8)
    private val LEGACY_HOST_IDENTITY = "host::\u0000".toByteArray(Charsets.UTF_8)

    data class AdbPacket(
        val command: Int,
        val arg0: Int,
        val arg1: Int,
        val payload: ByteArray
    ) {
        val commandName: String
            get() = commandToName(command)
    }

    fun commandToName(cmd: Int): String = when (cmd) {
        A_SYNC -> "SYNC"
        A_CNXN -> "CNXN"
        A_OPEN -> "OPEN"
        A_OKAY -> "OKAY"
        A_CLSE -> "CLSE"
        A_WRTE -> "WRTE"
        A_AUTH -> "AUTH"
        A_STLS -> "STLS"
        else -> "0x%08X".format(cmd)
    }

    fun generateConnect(skipChecksum: Boolean = true, modernBanner: Boolean = false): ByteArray {
        val version = if (skipChecksum) A_VERSION_SKIP_CHECKSUM else A_VERSION_MIN
        val banner = if (modernBanner) HOST_SYSTEM_IDENTITY else LEGACY_HOST_IDENTITY
        return generateMessage(
            command = A_CNXN,
            arg0 = version,
            arg1 = MAX_PAYLOAD_MODERN,
            payload = banner,
            includeChecksum = !skipChecksum
        )
    }

    fun generateStls(): ByteArray = generateMessage(
        command = A_STLS,
        arg0 = A_STLS_VERSION,
        arg1 = 0,
        payload = ByteArray(0),
        includeChecksum = false
    )

    fun generateAuth(authType: Int, data: ByteArray, includeChecksum: Boolean = true): ByteArray =
        generateMessage(
            command = A_AUTH,
            arg0 = authType,
            arg1 = 0,
            payload = data,
            includeChecksum = includeChecksum
        )

    fun generateOpen(localId: Int, destination: String, includeChecksum: Boolean): ByteArray {
        val nullTerminated = "$destination\u0000".toByteArray(Charsets.UTF_8)
        return generateMessage(
            command = A_OPEN,
            arg0 = localId,
            arg1 = 0,
            payload = nullTerminated,
            includeChecksum = includeChecksum
        )
    }

    fun generateReady(localId: Int, remoteId: Int, includeChecksum: Boolean): ByteArray =
        generateMessage(
            command = A_OKAY,
            arg0 = localId,
            arg1 = remoteId,
            payload = ByteArray(0),
            includeChecksum = includeChecksum
        )

    fun generateClose(localId: Int, remoteId: Int, includeChecksum: Boolean): ByteArray =
        generateMessage(
            command = A_CLSE,
            arg0 = localId,
            arg1 = remoteId,
            payload = ByteArray(0),
            includeChecksum = includeChecksum
        )

    fun generateWrite(
        localId: Int,
        remoteId: Int,
        data: ByteArray,
        includeChecksum: Boolean
    ): ByteArray = generateMessage(
        command = A_WRTE,
        arg0 = localId,
        arg1 = remoteId,
        payload = data,
        includeChecksum = includeChecksum
    )

    fun generateMessage(
        command: Int,
        arg0: Int,
        arg1: Int,
        payload: ByteArray,
        includeChecksum: Boolean = true
    ): ByteArray {
        val crc = if (includeChecksum) computePayloadChecksum(payload) else 0
        val magic = command.inv()
        return ByteBuffer.allocate(HEADER_LENGTH + payload.size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(command)
            .putInt(arg0)
            .putInt(arg1)
            .putInt(payload.size)
            .putInt(crc)
            .putInt(magic)
            .put(payload)
            .array()
    }

    fun readPacket(input: InputStream, protocolVersion: Int = A_VERSION_SKIP_CHECKSUM): AdbPacket {
        val header = readExact(input, HEADER_LENGTH)
        val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val command = buf.int
        val arg0 = buf.int
        val arg1 = buf.int
        val dataLength = buf.int
        val checksum = buf.int
        val magic = buf.int

        if (magic != command.inv()) {
            throw IOException("Corrupt ADB packet header magic (cmd=0x${command.toString(16)}, magic=0x${magic.toString(16)})")
        }
        if (dataLength < 0 || dataLength > HARD_MAX_PAYLOAD_GUARD) {
            throw IOException("ADB packet payload length out of safe bounds: $dataLength")
        }

        val payload = if (dataLength > 0) readExact(input, dataLength) else ByteArray(0)
        if (protocolVersion == A_VERSION_MIN && dataLength > 0 && command != A_CNXN) {
            val actualChecksum = computePayloadChecksum(payload)
            if (checksum != 0 && actualChecksum != checksum) {
                throw IOException("ADB payload checksum mismatch")
            }
        }
        return AdbPacket(command, arg0, arg1, payload)
    }

    fun computePayloadChecksum(payload: ByteArray): Int {
        var sum = 0
        for (b in payload) {
            sum += (b.toInt() and 0xFF)
        }
        return sum
    }

    private fun readExact(input: InputStream, count: Int): ByteArray {
        val out = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = input.read(out, offset, count - offset)
            if (read < 0) {
                throw EOFException("ADB socket closed after $offset/$count bytes")
            }
            offset += read
        }
        return out
    }
}
