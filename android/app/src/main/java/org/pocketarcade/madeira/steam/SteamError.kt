// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 Jfishin, 125hz (Madeira); Android port: Pocket Arcade contributors
// Madeira Converter Exception: see LICENSE-EXCEPTION.md
//
// Kotlin port of Madeira's SwiftSteam/Core/SteamError.swift.

package org.pocketarcade.madeira.steam

import java.util.Locale

/** Errors raised by the Steam sign-in, library and download code. */
sealed class SteamError {
    // Sign-in
    data class AuthenticationFailed(val reason: String) : SteamError()
    object InvalidCredentials : SteamError()
    object RateLimited : SteamError()
    object AccountDisabled : SteamError()
    object RsaKeyFetchFailed : SteamError()
    object QrCodeExpired : SteamError()
    object AuthSessionExpired : SteamError()
    data class Protobuf(val detail: String) : SteamError()

    // Connection
    object NoServersAvailable : SteamError()
    data class ConnectionFailed(val reason: String) : SteamError()
    object ConnectionTimeout : SteamError()
    object Disconnected : SteamError()
    /** The CM refused the logon with this EResult. */
    data class LogonDenied(val code: Int) : SteamError()
    object InvalidMessage : SteamError()

    // Library
    data class AppInfoNotFound(val appID: Int) : SteamError()
    data class DepotNotFound(val appID: Int) : SteamError()

    // Content
    data class ManifestFetchFailed(val reason: String) : SteamError()
    data class ChunkDownloadFailed(val reason: String) : SteamError()
    data class DecryptionFailed(val reason: String) : SteamError()
    object DecompressionFailed : SteamError()
    data class ChunkDecodeFailed(val format: String) : SteamError()
    object ChecksumMismatch : SteamError()
    data class DepotKeyNotFound(val depotID: Int) : SteamError()
    data class InsufficientDiskSpace(val needed: Long, val available: Long) : SteamError()

    val message: String
        get() = when (this) {
            is AuthenticationFailed -> reason
            InvalidCredentials -> "The account name or password is incorrect."
            RateLimited -> "Too many attempts. Please try again later."
            AccountDisabled -> "This Steam account has been disabled"
            RsaKeyFetchFailed -> "Failed to fetch RSA public key for password encryption"
            QrCodeExpired -> "QR code has expired. Please try again."
            AuthSessionExpired -> "The sign-in request expired. Start again."
            is Protobuf -> "Protocol buffer error: $detail"
            NoServersAvailable -> "No Steam servers are available."
            is ConnectionFailed -> "Connection failed: $reason"
            ConnectionTimeout -> "Steam did not respond in time. Check your internet connection and try again."
            Disconnected -> "Disconnected from Steam."
            is LogonDenied -> if (code in SIGN_IN_EXPIRED_CODES) "Your Steam sign-in is no longer valid. Sign in again."
                else "Steam refused the connection (code $code). Try again later."
            InvalidMessage -> "Received an invalid message from Steam."
            is AppInfoNotFound -> "Steam has no information for app $appID."
            is DepotNotFound -> "Steam has no Windows download for this game."
            is ManifestFetchFailed -> "Could not read the game's file list: $reason"
            is ChunkDownloadFailed -> "Download failed: $reason"
            is DecryptionFailed -> "Decryption failed: $reason"
            DecompressionFailed -> "Failed to decompress downloaded data."
            is ChunkDecodeFailed -> "Part of the game could not be decoded ($format). Try again; downloaded parts are kept."
            ChecksumMismatch -> "A downloaded part failed its checksum."
            is DepotKeyNotFound -> "Steam did not allow this account to download the game's files. Check that the account owns it."
            is InsufficientDiskSpace -> String.format(Locale.ROOT,
                "Not enough free space: the download needs %.1f GB and %.1f GB is available.",
                needed / 1e9, available / 1e9)
        }

    companion object {
        /** Logon EResults that mean the stored sign-in itself is unusable: InvalidPassword,
         *  AccessDenied, AccountNotFound, Revoked, Expired, InvalidSignature. */
        val SIGN_IN_EXPIRED_CODES = setOf(5, 15, 18, 26, 27, 94)
    }
}

class SteamException(val error: SteamError, cause: Throwable? = null) : Exception(error.message, cause)

/** Where a Steam Guard code comes from. */
enum class SteamGuardType { EMAIL, DEVICE }

/** Diagnostic log. Never pass tokens, passwords, codes or account names to it. */
object SteamLog {
    @Volatile var sink: ((String) -> Unit)? = null
    @Volatile var tracing = false
    fun event(text: String) { sink?.invoke(text) }
    fun trace(text: String) { if (tracing) sink?.invoke(text) }
}
