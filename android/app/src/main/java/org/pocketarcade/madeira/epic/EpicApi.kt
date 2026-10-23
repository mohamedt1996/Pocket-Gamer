// SPDX-License-Identifier: GPL-3.0-or-later
// Epic endpoint flow adapted from GameNative/Legendary; see EPIC-UPSTREAM.md.
package org.pocketarcade.madeira.epic

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Credentials
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

interface EpicHttp { suspend fun get(request: Request, limit: Int = 32 * 1024 * 1024): ByteArray }

class EpicNetwork : EpicHttp {
    private val client = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS).followSslRedirects(false).build()
    override suspend fun get(request: Request, limit: Int): ByteArray = suspendCancellableCoroutine { cont ->
        val call = client.newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(IOException("Epic connection failed. Check your internet connection."))
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val bytes = response.use {
                        if (!it.isSuccessful) throw IOException("Epic request failed (HTTP ${it.code})." +
                            if (it.code == 401) " Sign in again." else " Try again later.")
                        val body = it.body ?: throw IOException("Epic returned an empty response.")
                        require(body.contentLength() <= limit) { "Epic response is too large" }
                        body.byteStream().use { input ->
                            val output = java.io.ByteArrayOutputStream()
                            val buffer = ByteArray(65536)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                require(output.size().toLong() + count <= limit) { "Epic response is too large" }
                                output.write(buffer, 0, count)
                            }
                            output.toByteArray()
                        }
                    }
                    if (cont.isActive) cont.resume(bytes)
                } catch (_: Exception) {
                    // Do not surface URLs, signed queries, response bodies or token values.
                    if (cont.isActive) cont.resumeWithException(IOException("Epic request could not be completed. Check the connection and sign-in."))
                }
            }
        })
    }
}

data class EpicCredentials(val accountId: String, val name: String, val access: String, val refresh: String, val expiresAt: Long) {
    override fun toString() = "EpicCredentials(tokens=redacted)"
    fun json() = JSONObject().put("accountId", accountId).put("name", name).put("access", access)
        .put("refresh", refresh).put("expiresAt", expiresAt)
    companion object { fun parse(o: JSONObject) = EpicCredentials(o.getString("accountId"), o.getString("name"),
        o.getString("access"), o.getString("refresh"), o.getLong("expiresAt")) }
}
data class EpicGame(val app: String, val namespace: String, val catalog: String, val title: String, val image: String? = null) {
    fun json() = JSONObject().put("app", app).put("namespace", namespace).put("catalog", catalog).put("title", title).put("image", image)
    companion object { fun parse(o: JSONObject) = EpicGame(o.getString("app"), o.getString("namespace"), o.getString("catalog"),
        o.getString("title"), o.optString("image").takeIf { it.startsWith("https://") }) }
}
data class EpicBuild(val manifest: ByteArray, val mirrors: List<String>, val deploymentId: String = "")

class EpicApi(val http: EpicHttp = EpicNetwork()) {
    private fun request(url: String, token: String? = null) = Request.Builder().url(url)
        .header("User-Agent", EpicEndpoints.AGENT).apply { token?.let { header("Authorization", "Bearer $it") } }
    private suspend fun json(request: Request): JSONObject = JSONObject(String(http.get(request), Charsets.UTF_8))

    suspend fun signIn(input: String): EpicCredentials = token("authorization_code", "code", authorizationCode(input))
    suspend fun refresh(value: String): EpicCredentials = token("refresh_token", "refresh_token", value)
    private suspend fun token(grant: String, key: String, value: String): EpicCredentials {
        val body = FormBody.Builder().add("grant_type", grant).add(key, value).add("token_type", "eg1").build()
        val o = json(request(EpicEndpoints.ACCOUNT + "/account/api/oauth/token")
            .header("Authorization", Credentials.basic(EpicEndpoints.CLIENT_ID, EpicEndpoints.CLIENT_SECRET)).post(body).build())
        return EpicCredentials(o.getString("account_id"), o.optString("displayName", "Epic account"),
            o.getString("access_token"), o.getString("refresh_token"),
            System.currentTimeMillis() + o.getLong("expires_in") * 1000)
    }
    suspend fun exchange(access: String): String = json(request(EpicEndpoints.ACCOUNT + "/account/api/oauth/exchange", access).build()).getString("code")

    suspend fun library(access: String): List<EpicGame> {
        val out = linkedMapOf<String, EpicGame>()
        val cursors = HashSet<String>()
        var cursor: String? = null
        do {
            val url = EpicEndpoints.LIBRARY.toHttpUrl().newBuilder().addQueryParameter("includeMetadata", "true")
                .apply { cursor?.let { addQueryParameter("cursor", it) } }.build()
            val o = json(request(url.toString(), access).build())
            val records = o.optJSONArray("records")
            for (i in 0 until (records?.length() ?: 0)) {
                val r = records!!.getJSONObject(i)
                val platforms = r.optJSONArray("platform")
                if (platforms != null && platforms.length() > 0 && (0 until platforms.length()).none {
                        platforms.optString(it) in listOf("Windows", "Win32", "Win64") }) continue
                val app = r.optString("appName"); val ns = r.optString("namespace"); val id = r.optString("catalogItemId")
                if (app.isBlank() || ns.isBlank() || id.isBlank() || ns == "ue" || app == "1" || r.optString("sandboxType") == "PRIVATE") continue
                val catalog = catalog(access, ns, id)
                val categories = catalog.optJSONArray("categories")
                if ((0 until (categories?.length() ?: 0)).any { categories!!.getJSONObject(it).optString("path").startsWith("addons") }) continue
                val images = catalog.optJSONArray("keyImages")
                val image = (0 until (images?.length() ?: 0)).map { images!!.getJSONObject(it) }
                    .sortedBy { if (it.optString("type").contains("Wide", true)) 0 else 1 }
                    .map { it.optString("url") }.firstOrNull { it.startsWith("https://") }
                out[app] = EpicGame(app, ns, id, catalog.optString("title", app), image)
            }
            cursor = o.optJSONObject("responseMetadata")?.optString("nextCursor")?.takeIf { it.isNotBlank() && it != "null" }
            require(cursor == null || cursors.add(cursor!!)) { "Epic returned repeated library pages" }
        } while (cursor != null)
        return out.values.sortedBy { it.title.lowercase() }
    }
    private suspend fun catalog(access: String, ns: String, id: String): JSONObject {
        val url = EpicEndpoints.CATALOG.toHttpUrl().newBuilder().addPathSegment(ns).addPathSegments("bulk/items")
            .addQueryParameter("id", id).addQueryParameter("includeDLCDetails", "true")
            .addQueryParameter("includeMainGameDetails", "true").addQueryParameter("country", "US").addQueryParameter("locale", "en").build()
        return json(request(url.toString(), access).build()).optJSONObject(id) ?: JSONObject()
    }
    suspend fun build(access: String, game: EpicGame): EpicBuild {
        val url = EpicEndpoints.LAUNCHER.toHttpUrl().newBuilder().addPathSegments("launcher/api/public/assets/v2/platform/Windows/namespace")
            .addPathSegment(game.namespace).addPathSegment("catalogItem").addPathSegment(game.catalog).addPathSegment("app")
            .addPathSegment(game.app).addPathSegments("label/Live").build()
        val o = json(request(url.toString(), access).build())
        val element = o.getJSONArray("elements").getJSONObject(0)
        val manifests = element.getJSONArray("manifests")
        val config = element.optJSONObject("sidecar")?.optString("config").orEmpty()
        val deployment = if (config.isBlank()) "" else JSONObject(config).optString("deploymentId")
        val mirrors = (0 until manifests.length()).map { index ->
            val entry = manifests.getJSONObject(index)
            val uri = entry.getString("uri").toHttpUrl().newBuilder().scheme("https")
            entry.optJSONArray("queryParams")?.let { params -> for (j in 0 until params.length()) {
                val param = params.getJSONObject(j); uri.addQueryParameter(param.getString("name"), param.getString("value"))
            } }
            uri.build().toString()
        }
        require(mirrors.isNotEmpty()) { "Epic has no Windows download for this app" }
        var failure: Exception? = null
        for (mirror in mirrors) {
            try { return EpicBuild(http.get(request(mirror).build()), mirrors, deployment) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { failure = e }
        }
        throw IOException("Epic's download servers could not provide the manifest.", failure)
    }
    companion object {
        fun authorizationCode(input: String): String {
            val text = input.trim()
            val code = if (text.startsWith("{")) {
                val o = JSONObject(text); o.optString("authorizationCode").ifBlank { o.optString("code") }
            } else text
            require(code.matches(Regex("[A-Za-z0-9_-]{8,4096}"))) { "Paste the authorization code shown by Epic after signing in." }
            return code
        }
    }
}
