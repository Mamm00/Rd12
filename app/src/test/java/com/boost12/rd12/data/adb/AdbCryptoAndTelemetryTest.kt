package com.boost12.rd12.data.adb

import com.boost12.rd12.data.adb.crypto.AdbCredentialStore
import com.boost12.rd12.data.adb.crypto.AndroidPubkeyCodec
import com.boost12.rd12.data.adb.crypto.PairingAuthCipher
import com.boost12.rd12.data.adb.protocol.AdbConnectionSession
import com.boost12.rd12.data.adb.protocol.AdbProtocol
import com.boost12.rd12.data.adb.telemetry.DumpsysTelemetryParser
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.ByteArrayInputStream

class AdbCryptoAndTelemetryTest {

    @Test
    fun `SPAKE25519 + HKDF + AES-128-GCM client and adbd server derive matching session keys`() {
        val sixDigitCode = "482915".toByteArray(Charsets.UTF_8)
        val fakeTlsEkm = ByteArray(64) { idx -> (idx * 7 + 3).toByte() }
        val passwordMaterial = sixDigitCode + fakeTlsEkm

        val aliceClient = PairingAuthCipher.createClient(passwordMaterial)
        val bobServer = PairingAuthCipher.createServerForTest(passwordMaterial)

        assertThat(aliceClient.publicMessage).hasLength(32)
        assertThat(bobServer.publicMessage).hasLength(32)

        assertThat(aliceClient.initCipher(bobServer.publicMessage)).isTrue()
        assertThat(bobServer.initCipher(aliceClient.publicMessage)).isTrue()

        val keyMat = AdbCredentialStore.generateSelfSignedRsa2048()
        val encodedPubkey = AndroidPubkeyCodec.encodeWithName(keyMat.rsaPublicKey, "Boost12@Redmi12")
        val ciphertext = aliceClient.encrypt(encodedPubkey)
        assertThat(ciphertext).isNotNull()

        val decryptedByServer = bobServer.decrypt(ciphertext!!)
        assertThat(decryptedByServer).isEqualTo(encodedPubkey)

        // Round-trip decode 524-byte Android RSAPublicKey struct
        val rawStruct = AndroidPubkeyCodec.encodeStruct(keyMat.rsaPublicKey)
        val decodedKey = AndroidPubkeyCodec.decodeStruct(rawStruct)
        assertThat(decodedKey.modulus).isEqualTo(keyMat.rsaPublicKey.modulus)
        assertThat(decodedKey.publicExponent).isEqualTo(keyMat.rsaPublicKey.publicExponent)
    }

    @Test
    fun `SPAKE25519 wrong 6-digit pairing code fails PeerInfo decryption`() {
        val ekm = ByteArray(64) { 0x42 }
        val alice = PairingAuthCipher.createClient("111111".toByteArray() + ekm)
        val bob = PairingAuthCipher.createServerForTest("999999".toByteArray() + ekm)

        assertThat(alice.initCipher(bob.publicMessage)).isTrue()
        assertThat(bob.initCipher(alice.publicMessage)).isTrue()

        val ct = alice.encrypt("PeerInfoPayload".toByteArray())!!
        assertThat(bob.decrypt(ct)).isNull()
    }

    @Test
    fun `AdbProtocol round-trips CNXN, STLS, and shell exit code sentinel`() {
        val cnxnBytes = AdbProtocol.generateConnect(skipChecksum = false)
        val packet = AdbProtocol.readPacket(
            ByteArrayInputStream(cnxnBytes),
            AdbProtocol.A_VERSION_MIN
        )
        assertThat(packet.command).isEqualTo(AdbProtocol.A_CNXN)
        assertThat(packet.arg0).isEqualTo(AdbProtocol.A_VERSION_MIN)

        val (stdout, code) = AdbConnectionSession.extractExitCode("90\n__B12_EC__:0\n")
        assertThat(stdout).isEqualTo("90")
        assertThat(code).isEqualTo(0)
    }

    @Test
    fun `DumpsysTelemetryParser parses HyperOS battery, thermal, and endpoints defensively`() {
        val sampleBattery = """
            Current Battery Service state:
              AC powered: false
              USB powered: true
              Wireless powered: false
              status: 2
              level: 83
              scale: 100
              voltage: 4182
              temperature: 356
        """.trimIndent()

        val tel = DumpsysTelemetryParser.parse(
            batteryOutput = sampleBattery,
            thermalOutput = "Thermal Status: 0",
            displayOutput = "user_refresh_rate=90"
        )
        assertThat(tel.batteryPercent).isEqualTo(83)
        assertThat(tel.temperatureCelsius).isWithin(0.05f).of(35.6f)
        assertThat(tel.isCharging).isTrue()
        assertThat(tel.thermalStatusLabel).isEqualTo("Nominal")
        assertThat(tel.activeRefreshRateHz).isEqualTo(90)

        // Malformed output never crashes
        val emptyTel = DumpsysTelemetryParser.parse(null, "garbage", "")
        assertThat(emptyTel.batteryPercent).isNull()

        assertThat(DumpsysTelemetryParser.parseEndpointInput("192.168.1.44:38912"))
            .isEqualTo("192.168.1.44" to 38912)
        assertThat(DumpsysTelemetryParser.parseEndpointInput(":41222", "127.0.0.1"))
            .isEqualTo("127.0.0.1" to 41222)
        assertThat(DumpsysTelemetryParser.parseEndpointInput("invalid:99999")).isNull()
    }
}
