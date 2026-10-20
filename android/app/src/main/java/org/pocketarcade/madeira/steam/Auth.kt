// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 Jfishin, 125hz (Madeira); Android port: Pocket Arcade contributors
// Madeira Converter Exception: see LICENSE-EXCEPTION.md
//
// Kotlin port of Madeira's SwiftSteam Auth/ (SteamAuthAPI, SteamCredentialAuth,
// SteamQRAuth). Sign-in goes to Steam's IAuthenticationService over HTTPS; the
// password is RSA-encrypted with Steam's key before it leaves the device and is
// never stored. Request and response bodies are never logged.

package org.pocketarcade.madeira.steam

import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.math.BigInteger
import java.net.URLEncoder
import java.security.KeyFactory
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import javax.crypto.Cipher
import kotlin.coroutines.coroutineContext

/** IAuthenticationService over HTTPS. Steam reports most failures as HTTP 200 with an `x-eresult` header. */
class SteamAuthApi(private val http: HttpClient) {
    suspend fun call(method: String, body: ByteArray, get: Boolean = false): ByteArray {
        val base = "https://api.steampowered.com/IAuthenticationService/$method/v1/"
        val encoded = URLEncoder.encode(Base64.getEncoder().encodeToString(body), "UTF-8")
        val response = try {
            if (get) http.get("$base?input_protobuf_encoded=$encoded")
            else http.post(base, "input_protobuf_encoded=$encoded".toByteArray(), "application/x-www-form-urlencoded")
        } catch (e: SteamException) { throw e } catch (e: Exception) {
            throw SteamException(SteamError.AuthenticationFailed("Steam did not respond. Check your internet connection."), e)
        }
        if (response.code == 429) throw SteamException(SteamError.RateLimited)
        if (response.code != 200) throw SteamException(SteamError.AuthenticationFailed("Steam sign-in is unavailable right now (HTTP ${response.code})."))
        response.header("x-eresult")?.toIntOrNull()?.let { code ->
            if (code != 1) {
                SteamLog.trace("$method eresult=$code")
                throw SteamException(error(code))
            }
        }
        return response.body
    }

    companion object {
        fun error(code: Int): SteamError = when (code) {
            5, 18 -> SteamError.InvalidCredentials
            65, 88 -> SteamError.AuthenticationFailed("That Steam Guard code is not correct. Try again with a new code.")
            84, 87 -> SteamError.RateLimited
            17 -> SteamError.AccountDisabled
            9, 27 -> SteamError.AuthSessionExpired
            else -> SteamError.AuthenticationFailed("Steam refused the sign-in (code $code).")
        }
    }
}

/** Tokens Steam issues at the end of a sign-in. */
class AuthTokens(val refreshToken: String, val accessToken: String, val accountName: String)

/** What Steam Guard asks for after the password is accepted. */
data class SteamGuardPrompt(val codeType: SteamGuardType?, val canApprove: Boolean, val hint: String)

internal object AuthMessages {
    fun pollRequest(clientID: Long, requestID: ByteArray) = proto { uint64(1, clientID); bytes(2, requestID) }

    class Poll(val newClientID: Long, val newChallengeURL: String, val refreshToken: String, val accessToken: String, val accountName: String)

    fun poll(data: ByteArray): Poll {
        var cid = 0L; var url = ""; var refresh = ""; var access = ""; var name = ""
        data.proto().fields { f ->
            when (f) { 1 -> cid = varint(); 2 -> url = string(); 3 -> refresh = string(); 4 -> access = string(); 6 -> name = string(); else -> skip() }
        }
        return Poll(cid, url, refresh, access, name)
    }
}

/**
 * Account name + password + Steam Guard, split into begin / submit code / poll so the
 * interface can offer a code field and in-app approval at the same time.
 */
class SteamCredentialAuth(private val api: SteamAuthApi, private val deviceName: String) {
    private var clientID = 0L
    private var requestID = ByteArray(0)
    private var pollIntervalMs = 5000L
    private var steamID = 0L
    var accountName = ""; private set

    /** Starts a password sign-in. Null when Steam needs no confirmation; then call [pollForTokens]. */
    suspend fun begin(username: String, password: String): SteamGuardPrompt? {
        accountName = username
        val rsa = api.call("GetPasswordRSAPublicKey", proto { string(1, username) }, get = true)
        var mod = ""; var exp = ""; var timestamp = 0L
        rsa.proto().fields { f -> when (f) { 1 -> mod = string(); 2 -> exp = string(); 3 -> timestamp = varint(); else -> skip() } }
        if (mod.isEmpty()) throw SteamException(SteamError.InvalidCredentials)
        val encrypted = encryptPassword(password, mod, exp)
        val request = proto {
            string(1, deviceName)
            string(2, username)
            string(3, encrypted)
            uint64(4, timestamp)
            uint32(6, 1)          // k_EAuthTokenPlatformType_SteamClient: a token Valve's client accepts
            uint32(7, 1)          // persistent session
            string(8, "Client")
            message(9) { string(1, deviceName); uint32(2, 1) }
        }
        val response = api.call("BeginAuthSessionViaCredentials", request)
        val types = LinkedHashSet<Int>()
        var hint = ""
        response.proto().fields { f ->
            when (f) {
                1 -> clientID = varint()
                2 -> requestID = bytes()
                3 -> pollIntervalMs = (java.lang.Float.intBitsToFloat(fixed32()) * 1000).toLong()
                4 -> {
                    var type = 0; var message = ""
                    message().fields { g -> when (g) { 1 -> type = int32(); 2 -> message = string(); else -> skip() } }
                    types += type
                    if (type == 2 && hint.isEmpty()) hint = message
                }
                5 -> steamID = uint64Any()
                else -> skip()
            }
        }
        if (clientID == 0L) throw SteamException(SteamError.InvalidCredentials)
        if (pollIntervalMs <= 0) pollIntervalMs = 5000
        // k_EAuthSessionGuardType: 1 none, 2 email code, 3 device code, 4 device confirmation, 5 email confirmation.
        if (types.isEmpty() || types == setOf(1)) return null
        val codeType = if (3 in types) SteamGuardType.DEVICE else if (2 in types) SteamGuardType.EMAIL else null
        return SteamGuardPrompt(codeType, 4 in types || 5 in types, hint)
    }

    suspend fun submitSteamGuardCode(code: String, type: SteamGuardType) {
        api.call("UpdateAuthSessionWithSteamGuardCode", proto {
            uint64(1, clientID)
            fixed64(2, steamID)
            string(3, code.trim().uppercase())
            uint32(4, if (type == SteamGuardType.EMAIL) 2 else 3)
        })
    }

    /** Polls until Steam issues tokens (after a code, an in-app approval, or at once). Cancel to stop. */
    suspend fun pollForTokens(deadlineMs: Long = 5 * 60_000): AuthTokens {
        val deadline = System.currentTimeMillis() + deadlineMs
        while (System.currentTimeMillis() < deadline) {
            coroutineContext.ensureActive()
            val poll = AuthMessages.poll(api.call("PollAuthSessionStatus", AuthMessages.pollRequest(clientID, requestID)))
            if (poll.newClientID != 0L) clientID = poll.newClientID
            if (poll.refreshToken.isNotEmpty() && poll.accessToken.isNotEmpty()) {
                return AuthTokens(poll.refreshToken, poll.accessToken, poll.accountName.ifEmpty { accountName })
            }
            delay(maxOf(1000L, pollIntervalMs))
        }
        throw SteamException(SteamError.AuthSessionExpired)
    }

    companion object {
        /** RSA PKCS#1 v1.5 with Steam's key (hex modulus and exponent); base64 result. */
        fun encryptPassword(password: String, modulusHex: String, exponentHex: String): String {
            fun even(h: String) = if (h.length % 2 == 1) "0$h" else h
            val mod = even(modulusHex).unhex() ?: throw SteamException(SteamError.RsaKeyFetchFailed)
            val exp = even(exponentHex).unhex() ?: throw SteamException(SteamError.RsaKeyFetchFailed)
            return try {
                val key = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(BigInteger(1, mod), BigInteger(1, exp)))
                val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
                cipher.init(Cipher.ENCRYPT_MODE, key)
                Base64.getEncoder().encodeToString(cipher.doFinal(password.toByteArray(Charsets.UTF_8)))
            } catch (e: Exception) { throw SteamException(SteamError.RsaKeyFetchFailed, e) }
        }
    }
}

/** QR sign-in: Steam's mobile app scans the challenge URL (or opens it on the same device). */
class SteamQrAuth(private val api: SteamAuthApi, private val deviceName: String) {
    private var clientID = 0L
    private var requestID = ByteArray(0)
    private var pollIntervalMs = 5000L

    /** Starts a QR sign-in and returns the challenge URL to show as a QR code. */
    suspend fun begin(): String {
        val response = api.call("BeginAuthSessionViaQR", proto { string(1, deviceName); uint32(2, 1) })
        var url = ""
        response.proto().fields { f ->
            when (f) {
                1 -> clientID = varint()
                2 -> url = string()
                3 -> requestID = bytes()
                4 -> pollIntervalMs = (java.lang.Float.intBitsToFloat(fixed32()) * 1000).toLong()
                else -> skip()
            }
        }
        if (url.isEmpty()) throw SteamException(SteamError.AuthenticationFailed("Empty challenge URL from Steam"))
        if (pollIntervalMs <= 0) pollIntervalMs = 5000
        return url
    }

    /** Polls until the code is approved. [onNewChallenge] gets a rotated challenge URL. */
    suspend fun poll(onNewChallenge: (String) -> Unit): AuthTokens {
        repeat(120) {
            coroutineContext.ensureActive()
            val poll = AuthMessages.poll(api.call("PollAuthSessionStatus", AuthMessages.pollRequest(clientID, requestID)))
            if (poll.newClientID != 0L) clientID = poll.newClientID
            if (poll.newChallengeURL.isNotEmpty()) onNewChallenge(poll.newChallengeURL)
            if (poll.refreshToken.isNotEmpty() && poll.accessToken.isNotEmpty()) {
                return AuthTokens(poll.refreshToken, poll.accessToken, poll.accountName)
            }
            delay(maxOf(1000L, pollIntervalMs))
        }
        throw SteamException(SteamError.QrCodeExpired)
    }
}

/** JWT helpers. The claims select an account and an expiry; they are never treated as authenticated identity. */
object SteamJwt {
    private fun claims(token: String): Map<String, Any?>? {
        if (token.length > 8192) return null
        val parts = token.split(".")
        if (parts.size != 3) return null
        return try {
            @Suppress("UNCHECKED_CAST")
            Json.parse(String(Base64.getUrlDecoder().decode(parts[1].trimEnd('=')), Charsets.UTF_8)) as? Map<String, Any?>
        } catch (_: Exception) { null }
    }

    /** Expiry in Unix seconds, or null. */
    fun expiry(token: String): Long? = claims(token).long("exp")

    /** The SteamID the token names (`sub`), or null. */
    fun subject(token: String): Long? = claims(token).str("sub")?.toULongOrNull()?.toLong()

    fun usable(token: String, nowSeconds: Long = System.currentTimeMillis() / 1000): Boolean {
        if (token.isEmpty()) return false
        val exp = expiry(token) ?: return true
        return exp > nowSeconds
    }
}
