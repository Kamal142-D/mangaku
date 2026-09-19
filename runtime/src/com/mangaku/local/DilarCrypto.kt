package com.mangaku.local

// Adapted from Keiyoushi Dilar (Apache-2.0), pinned in vendor/keiyoushi.
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal object DilarCrypto {
    private const val CURVE_NAME = "secp256r1"
    private val ecKeyPair: KeyPair = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec(CURVE_NAME))
    }.generateKeyPair()

    private val clientPubRaw: ByteArray = pointToRaw(ecKeyPair.public as ECPublicKey)

    private val clientPubB64: String =
        Base64.encodeToString(clientPubRaw, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    fun decrypt(data: org.json.JSONObject): String {
        val serverPubRaw = Base64.decode(data.getString("epk"), Base64.URL_SAFE)
        val serverPubKey = rawToPoint(serverPubRaw)
        val iv = Base64.decode(data.getString("iv"), Base64.URL_SAFE)

        val sharedSecret = KeyAgreement.getInstance("ECDH").apply {
            init(ecKeyPair.private)
            doPhase(serverPubKey, true)
        }.generateSecret()

        val (salt, info) = when (data.getInt("v")) {
            1 -> {
                clientPubRaw + serverPubRaw to
                    "dilar.response.ecies.v1|${data.getInt("e")}".toByteArray()
            }

            2 -> {
                serverPubRaw + clientPubRaw to
                    "dilar.response.ecies.v2|${data.getInt("e")}".toByteArray()
            }

            3 -> {
                sha256(serverPubRaw + clientPubRaw) to
                    "dilar.response.ecies.v3|${data.getInt("e")}".toByteArray()
            }

            4 -> {
                sha256(clientPubRaw + serverPubRaw + iv) to
                    "dilar.response.ecies.v4|${data.getInt("e")}|${data.getString("iv")}"
                        .toByteArray()
            }

            5 -> {
                hmac(
                    key = iv,
                    data = serverPubRaw + clientPubRaw,
                ) to "dilar.response.ecies.v5|${data.getInt("e")}".toByteArray()
            }

            6 -> {
                sha256(sha256(clientPubRaw) + sha256(serverPubRaw) + iv) to
                    "dilar.response.ecies.v6|${data.getInt("e")}|${data.getString("iv")}".toByteArray()
            }

            7 -> {
                hkdfSha256(
                    ikm = iv,
                    salt = serverPubRaw,
                    info = "dilar.response.ecies.v7.salt".toByteArray(),
                    length = 32,
                ) to "dilar.response.ecies.v7|${data.getInt("e")}".toByteArray()
            }

            8 -> {
                sha256(
                    joinBytes(
                        u16(clientPubRaw.size),
                        clientPubRaw,
                        u16(serverPubRaw.size),
                        serverPubRaw,
                        u16(iv.size),
                        iv,
                    ),
                ) to "dilar.response.ecies.v8|${data.getInt("e")}|${iv.toHex()}".toByteArray()
            }

            9 -> {
                hmac(
                    key = iv,
                    data = joinBytes(
                        u16(serverPubRaw.size),
                        serverPubRaw,
                        u16(clientPubRaw.size),
                        clientPubRaw,
                    ),
                    algorithm = "HmacSHA512",
                ).copyOfRange(0, 32) to "dilar.response.ecies.v9|${data.getInt("e")}|${ sha256(iv).toHex().take(16)}".toByteArray()
            }

            else -> error("Unsupported encryption protocol version: ${data.getInt("v")}")
        }

        val key = hkdfSha256(
            ikm = sharedSecret,
            salt = salt,
            info = info,
            length = 32,
        )

        val ct = Base64.decode(data.getString("ct"), Base64.URL_SAFE)
        val tag = Base64.decode(data.getString("tag"), Base64.URL_SAFE)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        }

        return cipher.doFinal(ct + tag).toString(Charsets.UTF_8)
    }

    private fun u16(n: Int): ByteArray = byteArrayOf(((n shr 8) and 0xFF).toByte(), (n and 0xFF).toByte())

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun joinBytes(vararg parts: ByteArray): ByteArray {
        val size = parts.sumOf { it.size }
        val result = ByteArray(size)
        var offset = 0
        for (p in parts) {
            System.arraycopy(p, 0, result, offset, p.size)
            offset += p.size
        }
        return result
    }

    private fun pointToRaw(publicKey: ECPublicKey): ByteArray {
        val x = publicKey.w.affineX.toFixedBytes(32)
        val y = publicKey.w.affineY.toFixedBytes(32)
        return byteArrayOf(0x04) + x + y
    }

    private fun rawToPoint(raw: ByteArray): ECPublicKey {
        require(raw.size == 65 && raw[0] == 0x04.toByte()) { "Invalid P-256 raw public key" }
        val x = BigInteger(1, raw.copyOfRange(1, 33))
        val y = BigInteger(1, raw.copyOfRange(33, 65))

        val curveParams = AlgorithmParameters.getInstance("EC").apply {
            init(ECGenParameterSpec(CURVE_NAME))
        }.getParameterSpec(ECParameterSpec::class.java)

        val spec = ECPublicKeySpec(ECPoint(x, y), curveParams)
        return KeyFactory.getInstance("EC").generatePublic(spec) as ECPublicKey
    }

    private fun BigInteger.toFixedBytes(length: Int): ByteArray {
        val raw = toByteArray()
        return when {
            raw.size == length -> raw
            raw.size > length -> raw.copyOfRange(raw.size - length, raw.size) // drop sign byte
            else -> ByteArray(length - raw.size) + raw
        }
    }

    private fun hmac(
        key: ByteArray,
        data: ByteArray,
        algorithm: String = "HmacSHA256",
    ): ByteArray = Mac.getInstance(algorithm).apply {
        init(SecretKeySpec(key, algorithm))
    }.doFinal(data)

    // HKDF-SHA256

    private fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    private fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val prk = hmac(salt, ikm)
        val okm = ByteArrayOutputStream()
        var t = ByteArray(0)
        var counter = 1
        while (okm.size() < length) {
            t = hmac(prk, t + info + byteArrayOf(counter.toByte()))
            okm.write(t)
            counter++
        }
        return okm.toByteArray().copyOf(length)
    }


    val headers get() = mapOf("X-DH-Pub" to clientPubB64, "X-Crypto-Caps" to "1,2,3,4,5,6,7,8,9")
}
