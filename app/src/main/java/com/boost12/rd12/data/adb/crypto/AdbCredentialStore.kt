package com.boost12.rd12.data.adb.crypto

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.boost12.rd12.BuildConfig
import com.boost12.rd12.domain.model.SavedConnectionConfig
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.Date

data class AdbKeyMaterial(
    val privateKey: PrivateKey,
    val certificate: X509Certificate
) {
    val rsaPublicKey: RSAPublicKey
        get() = certificate.publicKey as RSAPublicKey

    val sha256Fingerprint: String
        get() {
            val digest = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
            return digest.take(8).joinToString(":") { "%02X".format(it) }
        }
}

enum class AppThemeMode {
    DARK,
    LIGHT,
    SYSTEM
}

/**
 * Stores ADB RSA-2048 private key, X.509v3 certificate, and Wireless Debugging endpoint metadata
 * inside AndroidX `EncryptedSharedPreferences` so subsequent reconnections are one-tap.
 */
class AdbCredentialStore(
    private val prefsProvider: () -> SharedPreferences
) {
    constructor(context: Context) : this(
        prefsProvider = { createEncryptedPrefsSafely(context.applicationContext) }
    )

    private val prefs: SharedPreferences by lazy(prefsProvider)

    @Volatile
    private var cachedKeyMaterial: AdbKeyMaterial? = null

    @Synchronized
    fun getOrCreateKeyMaterial(): AdbKeyMaterial {
        cachedKeyMaterial?.let { return it }

        val privB64 = prefs.getString(KEY_PRIVATE_PKCS8, null)
        val certB64 = prefs.getString(KEY_CERT_DER, null)
        if (!privB64.isNullOrBlank() && !certB64.isNullOrBlank()) {
            runCatching {
                val privBytes = Base64.getDecoder().decode(privB64)
                val certBytes = Base64.getDecoder().decode(certB64)
                val privateKey = KeyFactory.getInstance("RSA")
                    .generatePrivate(PKCS8EncodedKeySpec(privBytes))
                val certificate = CertificateFactory.getInstance("X.509")
                    .generateCertificate(ByteArrayInputStream(certBytes)) as X509Certificate
                return AdbKeyMaterial(privateKey, certificate).also { cachedKeyMaterial = it }
            }
        }

        val generated = generateSelfSignedRsa2048()
        prefs.edit()
            .putString(KEY_PRIVATE_PKCS8, Base64.getEncoder().encodeToString(generated.privateKey.encoded))
            .putString(KEY_CERT_DER, Base64.getEncoder().encodeToString(generated.certificate.encoded))
            .apply()
        cachedKeyMaterial = generated
        return generated
    }

    @Synchronized
    fun resetCredentials() {
        cachedKeyMaterial = null
        prefs.edit()
            .remove(KEY_PRIVATE_PKCS8)
            .remove(KEY_CERT_DER)
            .putBoolean(KEY_IS_PAIRED, false)
            .apply()
    }

    fun markPaired(host: String, pairingPort: Int) {
        prefs.edit()
            .putBoolean(KEY_IS_PAIRED, true)
            .putString(KEY_LAST_HOST, host)
            .putInt(KEY_LAST_PAIRING_PORT, pairingPort)
            .apply()
    }

    fun markUnpaired() {
        prefs.edit().putBoolean(KEY_IS_PAIRED, false).apply()
    }

    fun saveConnectEndpoint(host: String, connectPort: Int) {
        prefs.edit()
            .putString(KEY_LAST_HOST, host)
            .putInt(KEY_LAST_CONNECT_PORT, connectPort)
            .apply()
    }

    fun getSavedConfig(): SavedConnectionConfig {
        val fingerprint = runCatching { getOrCreateKeyMaterial().sha256Fingerprint }.getOrNull()
        return SavedConnectionConfig(
            isPaired = prefs.getBoolean(KEY_IS_PAIRED, false),
            lastHost = prefs.getString(KEY_LAST_HOST, DEFAULT_LOOPBACK_HOST) ?: DEFAULT_LOOPBACK_HOST,
            lastConnectPort = prefs.getInt(KEY_LAST_CONNECT_PORT, 0),
            lastPairingPort = prefs.getInt(KEY_LAST_PAIRING_PORT, 0),
            autoReconnect = prefs.getBoolean(KEY_AUTO_RECONNECT, true),
            certificateFingerprint = fingerprint
        )
    }

    fun setAutoReconnect(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_RECONNECT, enabled).apply()
    }

    fun getThemeMode(): AppThemeMode {
        val raw = prefs.getString(KEY_THEME_MODE, AppThemeMode.DARK.name)
        return runCatching { AppThemeMode.valueOf(raw ?: AppThemeMode.DARK.name) }
            .getOrDefault(AppThemeMode.DARK)
    }

    fun setThemeMode(mode: AppThemeMode) {
        prefs.edit().putString(KEY_THEME_MODE, mode.name).apply()
    }

    fun isDynamicColorEnabled(): Boolean = prefs.getBoolean(KEY_DYNAMIC_COLOR, false)

    fun setDynamicColorEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DYNAMIC_COLOR, enabled).apply()
    }

    fun isDebuggerModeEnabled(): Boolean =
        prefs.getBoolean(KEY_DEBUGGER_MODE, BuildConfig.DEFAULT_DEBUGGER_MODE)

    fun setDebuggerModeEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DEBUGGER_MODE, enabled).apply()
    }

    fun isBatteryGuideDismissed(): Boolean = prefs.getBoolean(KEY_BATTERY_GUIDE_DISMISSED, false)

    fun setBatteryGuideDismissed(dismissed: Boolean) {
        prefs.edit().putBoolean(KEY_BATTERY_GUIDE_DISMISSED, dismissed).apply()
    }

    companion object {
        const val DEFAULT_LOOPBACK_HOST = "127.0.0.1"
        private const val PREFS_FILE = "boost12_adb_secure_prefs"
        private const val KEY_PRIVATE_PKCS8 = "adb_rsa_priv_pkcs8"
        private const val KEY_CERT_DER = "adb_x509_cert_der"
        private const val KEY_IS_PAIRED = "adb_is_paired"
        private const val KEY_LAST_HOST = "adb_last_host"
        private const val KEY_LAST_CONNECT_PORT = "adb_last_connect_port"
        private const val KEY_LAST_PAIRING_PORT = "adb_last_pairing_port"
        private const val KEY_AUTO_RECONNECT = "adb_auto_reconnect"
        private const val KEY_THEME_MODE = "ui_theme_mode"
        private const val KEY_DYNAMIC_COLOR = "ui_dynamic_color"
        private const val KEY_DEBUGGER_MODE = "adb_debugger_mode"
        private const val KEY_BATTERY_GUIDE_DISMISSED = "miui_battery_guide_dismissed"

        private fun createEncryptedPrefsSafely(context: Context): SharedPreferences {
            return try {
                buildEncryptedPrefs(context)
            } catch (_: Exception) {
                // Recover cleanly if Android Keystore invalidated MasterKey after lockscreen change
                context.deleteSharedPreferences(PREFS_FILE)
                buildEncryptedPrefs(context)
            }
        }

        private fun buildEncryptedPrefs(context: Context): SharedPreferences {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            return EncryptedSharedPreferences.create(
                context,
                PREFS_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }

        fun generateSelfSignedRsa2048(): AdbKeyMaterial {
            val random = SecureRandom()
            val kpg = KeyPairGenerator.getInstance("RSA").apply {
                initialize(2048, random)
            }
            val keyPair = kpg.generateKeyPair()

            val now = System.currentTimeMillis()
            val notBefore = Date(now - 60_000L)
            val notAfter = Date(now + 10L * 365 * 24 * 60 * 60 * 1000L) // 10 years
            val issuer = X500Name("CN=Boost12 Redmi12, O=Boost12, C=US")
            val serial = BigInteger(128, random).abs().add(BigInteger.ONE)

            val pubKeyInfo = SubjectPublicKeyInfo.getInstance(keyPair.public.encoded)
            val certBuilder = X509v3CertificateBuilder(
                issuer,
                serial,
                notBefore,
                notAfter,
                issuer,
                pubKeyInfo
            )

            val extUtils = JcaX509ExtensionUtils()
            certBuilder.addExtension(
                Extension.basicConstraints,
                true,
                BasicConstraints(false)
            )
            certBuilder.addExtension(
                Extension.keyUsage,
                true,
                KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyEncipherment)
            )
            certBuilder.addExtension(
                Extension.subjectKeyIdentifier,
                false,
                extUtils.createSubjectKeyIdentifier(pubKeyInfo)
            )

            val bcProvider = BouncyCastleProvider()
            val signer = JcaContentSignerBuilder("SHA256withRSA")
                .setProvider(bcProvider)
                .build(keyPair.private)

            val holder = certBuilder.build(signer)
            val cert = JcaX509CertificateConverter()
                .setProvider(bcProvider)
                .getCertificate(holder)

            return AdbKeyMaterial(keyPair.private, cert)
        }
    }
}
