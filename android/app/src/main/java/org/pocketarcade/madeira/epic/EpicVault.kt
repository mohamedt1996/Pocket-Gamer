// SPDX-License-Identifier: GPL-3.0-or-later
package org.pocketarcade.madeira.epic

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Epic tokens never share Steam's vault or leave Keystore-backed encrypted storage. */
class EpicVault(context: Context) {
    private val prefs = context.getSharedPreferences("epic-account", Context.MODE_PRIVATE)
    private val alias = "pocketarcade.epic.account"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        }.generateKey()
    }
    @Synchronized fun load(): EpicCredentials? = try {
        val bytes = Base64.decode(prefs.getString("tokens", null) ?: return null, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, 12))
        EpicCredentials.parse(JSONObject(String(cipher.doFinal(bytes, 12, bytes.size - 12), Charsets.UTF_8)))
    } catch (_: Exception) { null }
    @Synchronized fun save(value: EpicCredentials) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val bytes = cipher.iv + cipher.doFinal(value.json().toString().toByteArray(Charsets.UTF_8))
        check(prefs.edit().putString("tokens", Base64.encodeToString(bytes, Base64.NO_WRAP)).commit()) { "Epic sign-in could not be stored securely" }
        check(load()?.refresh == value.refresh) { "Epic sign-in could not be verified in secure storage" }
    }
    @Synchronized fun clear() { prefs.edit().remove("tokens").commit() }
}
