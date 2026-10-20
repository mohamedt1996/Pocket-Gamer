// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 Pocket Arcade contributors
//
// Android implementations of the Steam client's platform seams: the CM
// WebSocket (OkHttp), a small preference store, and the sign-in vault.

package org.pocketarcade.madeira.platform

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.CompletableDeferred
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.pocketarcade.madeira.steam.CmTransport
import org.pocketarcade.madeira.steam.SteamError
import org.pocketarcade.madeira.steam.SteamException
import org.pocketarcade.madeira.steam.SteamStore
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** One OkHttp client for the app: WebSocket pings keep CM sockets alive behind NAT. */
object Net {
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .pingInterval(25, TimeUnit.SECONDS)
            .build()
    }
}

/** The CM WebSocket with Steam's "steamdataport" subprotocol; without it a CM ignores every message. */
class OkHttpCmTransport : CmTransport {
    @Volatile private var socket: WebSocket? = null
    @Volatile private var open = false
    override val isOpen: Boolean get() = open

    override suspend fun connect(url: String, listener: CmTransport.Listener) {
        val ready = CompletableDeferred<Unit>()
        val request = Request.Builder().url(url).header("Sec-WebSocket-Protocol", "steamdataport").build()
        socket = Net.client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { open = true; ready.complete(Unit) }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) { listener.onMessage(bytes.toByteArray()) }
            override fun onMessage(webSocket: WebSocket, text: String) {}
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                val was = open; open = false
                if (!ready.isCompleted) ready.completeExceptionally(SteamException(SteamError.ConnectionFailed("closed $code")))
                else if (was) listener.onClosed(null)
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val was = open; open = false
                if (!ready.isCompleted) ready.completeExceptionally(SteamException(SteamError.ConnectionFailed(t.message ?: "WebSocket failed"), t))
                else if (was) listener.onClosed(t)
            }
        })
        try { ready.await() } catch (e: Throwable) { socket?.cancel(); socket = null; throw e }
    }

    override suspend fun send(data: ByteArray) {
        val s = socket
        if (s == null || !open || !s.send(data.toByteString())) throw SteamException(SteamError.Disconnected)
    }

    override fun close() {
        open = false
        socket?.close(1000, null)
        socket = null
    }
}

class PrefsStore(context: Context, name: String = "steam") : SteamStore {
    private val prefs: SharedPreferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    override fun get(key: String): String? = prefs.getString(key, null)
    override fun put(key: String, value: String?) { prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply() }
}

/** The stored Steam sign-in. */
data class StoredSignIn(val accountName: String, val refreshToken: String, val accessToken: String, val steamID: Long, val savedAt: Long)

/**
 * Keeps the Steam sign-in encrypted with an AES-GCM key held by Android Keystore (this
 * device only, never exported or backed up). Nothing here is logged.
 */
class TokenVault(context: Context) {
    private val prefs = context.getSharedPreferences("steam-signin", Context.MODE_PRIVATE)
    private val alias = "pocketarcade.steam.signin"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build())
        return generator.generateKey()
    }

    private fun seal(text: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val sealed = cipher.iv + cipher.doFinal(text.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(sealed, Base64.NO_WRAP)
    }

    private fun open(text: String): String? = try {
        val raw = Base64.decode(text, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, 12))
        String(cipher.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8)
    } catch (_: Exception) { null }

    @Synchronized fun load(): StoredSignIn? {
        val sealed = prefs.getString("v1", null) ?: return null
        val plain = open(sealed) ?: return null
        val parts = plain.split('\n')
        if (parts.size < 5) return null
        return StoredSignIn(parts[0], parts[1], parts[2], parts[3].toLongOrNull() ?: 0, parts[4].toLongOrNull() ?: 0)
    }

    /** Returns whether the sign-in reads back as stored. */
    @Synchronized fun save(s: StoredSignIn): Boolean {
        if (s.accountName.contains('\n') || s.refreshToken.contains('\n') || s.accessToken.contains('\n')) return false
        return try {
            prefs.edit().putString("v1", seal(listOf(s.accountName, s.refreshToken, s.accessToken, s.steamID, s.savedAt).joinToString("\n"))).commit() &&
                load()?.refreshToken == s.refreshToken
        } catch (_: Exception) { false }
    }

    @Synchronized fun clear() { prefs.edit().remove("v1").commit() }
}
