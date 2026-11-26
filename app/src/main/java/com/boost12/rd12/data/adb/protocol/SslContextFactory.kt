package com.boost12.rd12.data.adb.protocol

import android.annotation.SuppressLint
import com.boost12.rd12.data.adb.crypto.AdbKeyMaterial
import org.conscrypt.Conscrypt
import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager

/**
 * Creates a TLSv1.3 [SSLContext] backed by Conscrypt so both client-certificate `A_STLS`
 * authentication and RFC 5705 `exportKeyingMaterial("adb-label\u0000", 64)` work identically
 * across MIUI and HyperOS builds without relying on restricted hidden APIs.
 */
object SslContextFactory {
    const val ADB_EKM_LABEL = "adb-label\u0000"
    const val ADB_EKM_LENGTH = 64
    private const val CLIENT_KEY_ALIAS = "boost12_adb_rsa"

    fun createTls13Context(keyMaterial: AdbKeyMaterial): Pair<SSLContext, Boolean> {
        var usingBundledConscrypt = false
        val sslContext = try {
            val provider = Conscrypt.newProvider()
            usingBundledConscrypt = true
            SSLContext.getInstance("TLSv1.3", provider)
        } catch (_: Throwable) {
            SSLContext.getInstance("TLSv1.3")
        }

        sslContext.init(
            arrayOf<KeyManager>(createClientKeyManager(keyMaterial)),
            arrayOf<TrustManager>(createPeerTrustManager()),
            SecureRandom()
        )
        return sslContext to usingBundledConscrypt
    }

    fun exportKeyingMaterial(
        sslSocket: SSLSocket,
        usingBundledConscrypt: Boolean,
        length: Int = ADB_EKM_LENGTH
    ): ByteArray {
        if (usingBundledConscrypt && Conscrypt.isConscrypt(sslSocket)) {
            return Conscrypt.exportKeyingMaterial(sslSocket, ADB_EKM_LABEL, null, length)
                ?: throw SSLException("Conscrypt returned null keying material")
        }
        return try {
            val platformConscrypt = Class.forName("com.android.org.conscrypt.Conscrypt")
            val method = platformConscrypt.getMethod(
                "exportKeyingMaterial",
                SSLSocket::class.java,
                String::class.java,
                ByteArray::class.java,
                Int::class.javaPrimitiveType
            )
            method.invoke(null, sslSocket, ADB_EKM_LABEL, null, length) as? ByteArray
                ?: throw SSLException("Platform Conscrypt returned null keying material")
        } catch (e: SSLException) {
            throw e
        } catch (t: Throwable) {
            throw SSLException("Failed to export TLSv1.3 keying material for SPAKE2+", t)
        }
    }

    private fun createClientKeyManager(keyMaterial: AdbKeyMaterial): X509ExtendedKeyManager {
        return object : X509ExtendedKeyManager() {
            override fun getClientAliases(
                keyType: String?,
                issuers: Array<out Principal>?
            ): Array<String>? {
                return if (keyType.equals("RSA", ignoreCase = true)) {
                    arrayOf(CLIENT_KEY_ALIAS)
                } else {
                    null
                }
            }

            override fun chooseClientAlias(
                keyTypes: Array<out String>?,
                issuers: Array<out Principal>?,
                socket: Socket?
            ): String? {
                return if (keyTypes?.any { it.equals("RSA", ignoreCase = true) } == true) {
                    CLIENT_KEY_ALIAS
                } else {
                    CLIENT_KEY_ALIAS
                }
            }

            override fun chooseEngineClientAlias(
                keyTypes: Array<out String>?,
                issuers: Array<out Principal>?,
                engine: SSLEngine?
            ): String {
                return CLIENT_KEY_ALIAS
            }

            override fun getServerAliases(
                keyType: String?,
                issuers: Array<out Principal>?
            ): Array<String>? = null

            override fun chooseServerAlias(
                keyType: String?,
                issuers: Array<out Principal>?,
                socket: Socket?
            ): String? = null

            override fun getCertificateChain(alias: String?): Array<X509Certificate>? {
                return if (alias == CLIENT_KEY_ALIAS) arrayOf(keyMaterial.certificate) else null
            }

            override fun getPrivateKey(alias: String?): PrivateKey? {
                return if (alias == CLIENT_KEY_ALIAS) keyMaterial.privateKey else null
            }
        }
    }

    /**
     * Per AOSP `adb/tls/tls_connection.cpp`, `adbd` uses self-signed ephemeral certificates;
     * authentication during pairing is bound via EKM + SPAKE2+, and during `A_STLS` via mutually
     * pinned RSA keys negotiated during pairing.
     */
    @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
    private fun createPeerTrustManager(): X509TrustManager {
        return object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
    }
}
