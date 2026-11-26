package com.boost12.rd12.data.adb.protocol

import com.boost12.rd12.data.adb.crypto.AdbKeyMaterial
import com.boost12.rd12.data.adb.crypto.AndroidPubkeyCodec
import com.boost12.rd12.domain.model.AdbErrorKind
import com.boost12.rd12.domain.model.ShellExecutionResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket

class AdbSessionException(
    val kind: AdbErrorKind,
    override val message: String,
    cause: Throwable? = null
) : IOException(message, cause)

/**
 * Active multiplexed AOSP ADB connection over TCP + `A_STLS` TLSv1.3 upgrade.
 * Supports concurrent/sequential `shell:` streams, exit-code extraction, authorization-revocation
 * detection, and immediate teardown notification when sleep/Doze closes the underlying socket.
 */
class AdbConnectionSession(
    val host: String,
    val port: Int,
    private val keyMaterial: AdbKeyMaterial,
    private val onProtocolTrace: (String) -> Unit = {},
    private val onUnexpectedDisconnect: (Throwable) -> Unit = {}
) : Closeable {

    private val rawSocket = Socket()
    private val writeLock = Any()

    @Volatile
    private var activeInput: InputStream? = null

    @Volatile
    private var activeOutput: OutputStream? = null

    @Volatile
    var isTlsUpgraded: Boolean = false
        private set

    @Volatile
    private var negotiatedProtocolVersion: Int = AdbProtocol.A_VERSION_SKIP_CHECKSUM

    private val nextLocalStreamId = AtomicInteger(1)
    private val openStreams = ConcurrentHashMap<Int, ShellStreamSlot>()
    private val handshakeDeferred = CompletableDeferred<Unit>()
    private val closed = AtomicBoolean(false)
    private val closedIntentionally = AtomicBoolean(false)

    private var readerThread: Thread? = null

    val isActive: Boolean
        get() = !closed.get() && handshakeDeferred.isCompleted && !rawSocket.isClosed

    private class ShellStreamSlot(val localId: Int) {
        @Volatile
        var remoteId: Int = 0
        val buffer = ByteArrayOutputStream()
        val openedDeferred = CompletableDeferred<Unit>()
        val closedDeferred = CompletableDeferred<ByteArray>()
    }

    suspend fun connect(timeoutMs: Long = 10_000L) = withContext(Dispatchers.IO) {
        try {
            onProtocolTrace("Opening ADB TCP socket to $host:$port")
            rawSocket.tcpNoDelay = true
            rawSocket.keepAlive = true
            rawSocket.connect(InetSocketAddress(host, port), timeoutMs.toInt().coerceAtLeast(3_000))
            activeInput = rawSocket.inputStream
            activeOutput = rawSocket.outputStream

            startPacketReaderThread()

            sendPacket(AdbProtocol.generateConnect(skipChecksum = false))
            onProtocolTrace("Sent initial A_CNXN; awaiting A_STLS or A_CNXN")

            withTimeout(timeoutMs) {
                handshakeDeferred.await()
            }
        } catch (e: TimeoutCancellationException) {
            close()
            throw AdbSessionException(
                AdbErrorKind.CONNECTION_REFUSED,
                "Timed out establishing ADB session with $host:$port.",
                e
            )
        } catch (e: AdbSessionException) {
            close()
            throw e
        } catch (e: SSLHandshakeException) {
            close()
            throw AdbSessionException(
                AdbErrorKind.UNAUTHORIZED_REVOKED,
                "ADB TLS certificate rejected — Wireless Debugging authorization may have been revoked. Re-pair Boost12.",
                e
            )
        } catch (e: Exception) {
            close()
            val msg = e.message.orEmpty()
            val kind = when {
                msg.contains("revoked", ignoreCase = true) ||
                    msg.contains("alert", ignoreCase = true) ||
                    msg.contains("certificate", ignoreCase = true) -> AdbErrorKind.UNAUTHORIZED_REVOKED
                msg.contains("Connection refused", ignoreCase = true) -> AdbErrorKind.CONNECTION_REFUSED
                else -> AdbErrorKind.CONNECTION_REFUSED
            }
            throw AdbSessionException(
                kind,
                if (kind == AdbErrorKind.UNAUTHORIZED_REVOKED) {
                    "Wireless Debugging rejected our saved key. Re-pair with a 6-digit pairing code."
                } else {
                    "Unable to connect to $host:$port (${e.message ?: "socket error"}). Note: Wireless Debugging changes its connect port after reboot or toggling Wi-Fi."
                },
                e
            )
        }
    }

    suspend fun executeShell(
        rawCommand: String,
        timeoutMs: Long = 8_000L
    ): ShellExecutionResult = withContext(Dispatchers.IO) {
        if (!isActive) {
            throw AdbSessionException(
                AdbErrorKind.SOCKET_DROPPED_DOZE,
                "ADB session is not connected."
            )
        }

        val startNs = System.nanoTime()
        val localId = nextLocalStreamId.getAndIncrement()
        val slot = ShellStreamSlot(localId)
        openStreams[localId] = slot

        // Wrap command so exit code is deterministically captured without needing root or pty
        val wrappedService = "shell:($rawCommand) 2>&1; printf '\\n$EXIT_CODE_MARKER%d\\n' $?"
        val includeChecksum = !isTlsUpgraded && negotiatedProtocolVersion == AdbProtocol.A_VERSION_MIN

        try {
            sendPacket(AdbProtocol.generateOpen(localId, wrappedService, includeChecksum))

            val rawBytes = withTimeout(timeoutMs) {
                slot.openedDeferred.await()
                slot.closedDeferred.await()
            }

            val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
            val decoded = rawBytes.toString(Charsets.UTF_8)
            val (cleanOutput, exitCode) = extractExitCode(decoded)

            ShellExecutionResult(
                command = rawCommand,
                stdout = cleanOutput,
                stderr = "",
                exitCode = exitCode,
                durationMs = elapsedMs,
                timedOut = false
            )
        } catch (_: TimeoutCancellationException) {
            val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
            val partial = synchronized(slot.buffer) {
                slot.buffer.toByteArray().toString(Charsets.UTF_8)
            }
            if (slot.remoteId != 0) {
                runCatching {
                    sendPacket(AdbProtocol.generateClose(localId, slot.remoteId, includeChecksum))
                }
            }
            ShellExecutionResult(
                command = rawCommand,
                stdout = partial.trim(),
                stderr = "Command timed out after ${timeoutMs}ms",
                exitCode = 124,
                durationMs = elapsedMs,
                timedOut = true
            )
        } finally {
            openStreams.remove(localId)
        }
    }

    private fun startPacketReaderThread() {
        var sentLegacySignature = false
        readerThread = Thread({
            try {
                while (!closed.get() && !Thread.currentThread().isInterrupted) {
                    val input = activeInput ?: break
                    val packet = AdbProtocol.readPacket(input, negotiatedProtocolVersion)

                    when (packet.command) {
                        AdbProtocol.A_STLS -> {
                            onProtocolTrace("Received A_STLS; upgrading socket to TLSv1.3")
                            sendPacket(AdbProtocol.generateStls())
                            val (sslContext, _) = SslContextFactory.createTls13Context(keyMaterial)
                            val tlsSocket = sslContext.socketFactory.createSocket(
                                rawSocket,
                                host,
                                port,
                                true
                            ) as SSLSocket
                            tlsSocket.enabledProtocols = arrayOf("TLSv1.3", "TLSv1.2")
                            tlsSocket.startHandshake()

                            synchronized(writeLock) {
                                activeInput = tlsSocket.inputStream
                                activeOutput = tlsSocket.outputStream
                                isTlsUpgraded = true
                                negotiatedProtocolVersion = AdbProtocol.A_VERSION_SKIP_CHECKSUM
                            }
                            onProtocolTrace("TLSv1.3 handshake established with adbd")
                        }

                        AdbProtocol.A_AUTH -> {
                            if (isTlsUpgraded) continue
                            if (packet.arg0 == AdbProtocol.ADB_AUTH_TOKEN) {
                                if (!sentLegacySignature) {
                                    sentLegacySignature = true
                                    val signature = AndroidPubkeyCodec.signAdbAuthToken(
                                        keyMaterial.privateKey,
                                        packet.payload
                                    )
                                    sendPacket(
                                        AdbProtocol.generateAuth(
                                            AdbProtocol.ADB_AUTH_SIGNATURE,
                                            signature,
                                            includeChecksum = true
                                        )
                                    )
                                } else {
                                    throw AdbSessionException(
                                        AdbErrorKind.UNAUTHORIZED_REVOKED,
                                        "ADB RSA key is not authorized by adbd. Pair Wireless Debugging first."
                                    )
                                }
                            }
                        }

                        AdbProtocol.A_CNXN -> {
                            negotiatedProtocolVersion = packet.arg0
                            val banner = packet.payload.toString(Charsets.UTF_8).trim('\u0000')
                            onProtocolTrace("A_CNXN established (proto=0x${packet.arg0.toString(16)}, peer=$banner)")
                            handshakeDeferred.complete(Unit)
                        }

                        AdbProtocol.A_OKAY -> {
                            val remoteId = packet.arg0
                            val localId = packet.arg1
                            val slot = openStreams[localId]
                            if (slot != null) {
                                slot.remoteId = remoteId
                                slot.openedDeferred.complete(Unit)
                            }
                        }

                        AdbProtocol.A_WRTE -> {
                            val remoteId = packet.arg0
                            val localId = packet.arg1
                            val slot = openStreams[localId]
                            val includeChecksum =
                                !isTlsUpgraded && negotiatedProtocolVersion == AdbProtocol.A_VERSION_MIN
                            if (slot != null) {
                                slot.remoteId = remoteId
                                if (!slot.openedDeferred.isCompleted) {
                                    slot.openedDeferred.complete(Unit)
                                }
                                synchronized(slot.buffer) {
                                    slot.buffer.write(packet.payload)
                                }
                                sendPacket(
                                    AdbProtocol.generateReady(localId, remoteId, includeChecksum)
                                )
                            }
                        }

                        AdbProtocol.A_CLSE -> {
                            val localId = packet.arg1
                            val slot = openStreams.remove(localId)
                            val includeChecksum =
                                !isTlsUpgraded && negotiatedProtocolVersion == AdbProtocol.A_VERSION_MIN
                            if (slot != null) {
                                if (!slot.openedDeferred.isCompleted) {
                                    slot.openedDeferred.complete(Unit)
                                }
                                val bytes = synchronized(slot.buffer) { slot.buffer.toByteArray() }
                                slot.closedDeferred.complete(bytes)
                                if (slot.remoteId != 0) {
                                    runCatching {
                                        sendPacket(
                                            AdbProtocol.generateClose(
                                                localId,
                                                slot.remoteId,
                                                includeChecksum
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                val mapped = when {
                    t is AdbSessionException -> t
                    t is SSLHandshakeException ||
                        t.message?.contains("alert", ignoreCase = true) == true ||
                        t.message?.contains("certificate", ignoreCase = true) == true ->
                        AdbSessionException(
                            AdbErrorKind.UNAUTHORIZED_REVOKED,
                            "ADB authorization revoked or TLS key rejected by Wireless Debugging.",
                            t
                        )
                    else -> AdbSessionException(
                        AdbErrorKind.SOCKET_DROPPED_DOZE,
                        "ADB socket closed (${t.message ?: "connection reset"}).",
                        t
                    )
                }

                if (!handshakeDeferred.isCompleted) {
                    handshakeDeferred.completeExceptionally(mapped)
                }

                val wasIntentional = closedIntentionally.get()
                teardownInternal(mapped)
                if (!wasIntentional) {
                    onUnexpectedDisconnect(mapped)
                }
            }
        }, "Boost12-AdbReader").apply {
            isDaemon = true
            start()
        }
    }

    private fun sendPacket(bytes: ByteArray) {
        synchronized(writeLock) {
            val out = activeOutput ?: throw IOException("ADB output stream closed")
            out.write(bytes)
            out.flush()
        }
    }

    override fun close() {
        closedIntentionally.set(true)
        teardownInternal(IOException("Session closed"))
    }

    private fun teardownInternal(cause: Throwable) {
        if (!closed.compareAndSet(false, true)) return
        runCatching { rawSocket.close() }
        readerThread?.interrupt()
        for ((_, slot) in openStreams) {
            if (!slot.openedDeferred.isCompleted) {
                slot.openedDeferred.completeExceptionally(cause)
            }
            if (!slot.closedDeferred.isCompleted) {
                slot.closedDeferred.completeExceptionally(cause)
            }
        }
        openStreams.clear()
    }

    companion object {
        internal const val EXIT_CODE_MARKER = "__B12_EC__:"

        internal fun extractExitCode(rawOutput: String): Pair<String, Int> {
            val normalized = rawOutput.replace("\r\n", "\n")
            val markerIdx = normalized.lastIndexOf(EXIT_CODE_MARKER)
            if (markerIdx < 0) {
                return normalized.trim() to 0
            }
            val before = normalized.substring(0, markerIdx).trimEnd('\n', ' ')
            val after = normalized.substring(markerIdx + EXIT_CODE_MARKER.length)
                .lineSequence()
                .firstOrNull()
                ?.trim()
                .orEmpty()
            val code = after.toIntOrNull() ?: 0
            return before.trim() to code
        }
    }
}
