// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 Jfishin, 125hz (Madeira); Android port: Pocket Arcade contributors
// Madeira Converter Exception: see LICENSE-EXCEPTION.md
//
// Port of Madeira's SteamSignIn / SteamSignInModel: the stored sign-in and the
// sign-in sheet's state (password + Steam Guard code or in-app approval, or a
// QR code). The refresh token stays in the Keystore-backed vault on this device.

package org.pocketarcade.madeira.model

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.pocketarcade.madeira.platform.StoredSignIn
import org.pocketarcade.madeira.platform.TokenVault
import org.pocketarcade.madeira.steam.AuthTokens
import org.pocketarcade.madeira.steam.Credentials
import org.pocketarcade.madeira.steam.SteamAuthApi
import org.pocketarcade.madeira.steam.SteamCredentialAuth
import org.pocketarcade.madeira.steam.SteamError
import org.pocketarcade.madeira.steam.SteamException
import org.pocketarcade.madeira.steam.SteamGuardPrompt
import org.pocketarcade.madeira.steam.SteamJwt
import org.pocketarcade.madeira.steam.SteamLog
import org.pocketarcade.madeira.steam.SteamQrAuth

class SteamAccount(private val vault: TokenVault, private val api: SteamAuthApi, private val deviceName: String, private val scope: CoroutineScope) {
    data class SignInState(
        val busy: Boolean = false,
        val error: String? = null,
        val guard: SteamGuardPrompt? = null,
        val qrUrl: String? = null,
    )

    private val _accountName = MutableStateFlow(credentials()?.accountName)
    /** The signed-in account's name, or null. */
    val accountName: StateFlow<String?> = _accountName
    private val _state = MutableStateFlow(SignInState())
    val state: StateFlow<SignInState> = _state

    private var task: Job? = null
    private var password: SteamCredentialAuth? = null
    /** Called after a sign-in is stored or removed. */
    var onChange: (() -> Unit)? = null

    val signedIn: Boolean get() = _accountName.value != null

    /** The stored account and refresh token, or null when none is usable (its own expiry passed). */
    fun credentials(): Credentials? {
        val s = vault.load() ?: return null
        val name = s.accountName.trim()
        if (name.isEmpty() || s.refreshToken.isEmpty() || !SteamJwt.usable(s.refreshToken)) return null
        return Credentials(name, s.refreshToken)
    }

    fun beginQR() {
        cancel()
        _state.value = SignInState(busy = true)
        task = scope.launch {
            try {
                val qr = SteamQrAuth(api, deviceName)
                val url = qr.begin()
                _state.value = SignInState(qrUrl = url)
                val tokens = qr.poll { rotated -> _state.value = _state.value.copy(qrUrl = rotated) }
                finish(tokens, "qr")
            } catch (e: CancellationException) { throw e } catch (e: Exception) { fail(e, "qr") }
        }
    }

    fun signIn(account: String, secret: String) {
        cancel()
        val name = account.trim()
        if (name.isEmpty() || secret.isEmpty()) return
        _state.value = SignInState(busy = true)
        task = scope.launch {
            try {
                val auth = SteamCredentialAuth(api, deviceName)
                password = auth
                val prompt = auth.begin(name, secret)
                _state.value = SignInState(busy = prompt == null, guard = prompt)
                finish(auth.pollForTokens(), "password")
            } catch (e: CancellationException) { throw e } catch (e: Exception) { fail(e, "password") }
        }
    }

    /** A Steam Guard code; the running poll then receives the tokens. */
    fun submitGuardCode(code: String) {
        val type = _state.value.guard?.codeType ?: return
        val auth = password ?: return
        if (code.isBlank()) return
        _state.value = _state.value.copy(busy = true, error = null)
        scope.launch {
            try { auth.submitSteamGuardCode(code, type) }
            catch (e: CancellationException) { throw e } catch (e: Exception) { _state.value = _state.value.copy(busy = false, error = message(e)) }
        }
    }

    fun cancel() {
        task?.cancel(); task = null; password = null
        _state.value = SignInState()
    }

    fun clearError() { _state.value = _state.value.copy(error = null) }

    fun signOut() {
        cancel()
        vault.clear()
        _accountName.value = null
        SteamLog.event("[steam-signin] signed out")
        onChange?.invoke()
    }

    private fun fail(e: Exception, method: String) {
        _state.value = SignInState(error = message(e))
        SteamLog.event("[steam-signin] sign-in failed method=$method reason=${reason(e)}")
    }

    private fun finish(tokens: AuthTokens, method: String) {
        task = null; password = null
        val steamID = SteamJwt.subject(tokens.refreshToken) ?: 0
        val ok = vault.save(StoredSignIn(tokens.accountName, tokens.refreshToken, tokens.accessToken, steamID, System.currentTimeMillis()))
        if (!ok) {
            _state.value = SignInState(error = "Steam accepted the sign-in, but this device could not store it securely. Try again.")
            SteamLog.event("[steam-signin] keystore store failed method=$method")
            return
        }
        _state.value = SignInState()
        _accountName.value = credentials()?.accountName
        SteamLog.event("[steam-signin] signed in method=$method")
        onChange?.invoke()
    }

    companion object {
        fun message(e: Throwable): String = when (e) {
            is SteamException -> e.error.message
            is java.net.UnknownHostException, is java.net.ConnectException -> "No internet connection. Connect and try again."
            is java.net.SocketTimeoutException -> "Steam did not respond in time. Try again."
            else -> e.message ?: e.javaClass.simpleName
        }

        /** Short, credential-free reason for the log. */
        fun reason(e: Throwable): String = when (val err = (e as? SteamException)?.error) {
            SteamError.InvalidCredentials -> "credentials"
            SteamError.RateLimited -> "rate-limited"
            SteamError.AccountDisabled -> "disabled"
            SteamError.AuthSessionExpired -> "expired"
            SteamError.QrCodeExpired -> "qr-expired"
            SteamError.RsaKeyFetchFailed -> "rsa"
            is SteamError.Protobuf -> "protobuf"
            is SteamError.AuthenticationFailed -> "refused"
            is SteamError.LogonDenied -> "logon-${err.code}"
            SteamError.ConnectionTimeout -> "timeout"
            is SteamError.DepotKeyNotFound -> "depot-key"
            is SteamError.DepotNotFound -> "no-windows-depot"
            is SteamError.InsufficientDiskSpace -> "disk-space"
            SteamError.ChecksumMismatch -> "checksum"
            is SteamError.ChunkDecodeFailed -> "decode-${err.format}"
            is SteamError.ManifestFetchFailed -> "manifest"
            is SteamError.ChunkDownloadFailed -> "chunk"
            else -> e.javaClass.simpleName
        }
    }
}
