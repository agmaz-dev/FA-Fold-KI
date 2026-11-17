package de.foldki.app.github

import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Small GitHub REST client (api.github.com) with the user's own token. Answers stay raw JSON text;
 * the agent tools shorten them before they go to the model.
 */
class GitHubApi(private val token: String) {
    class HttpError(val code: Int, body: String) : IOException("GitHub antwortet $code: ${body.take(400)}")

    fun get(path: String): String = call("GET", path, null)
    fun post(path: String, json: String): String = call("POST", path, json)
    fun patch(path: String, json: String): String = call("PATCH", path, json)
    fun put(path: String, json: String): String = call("PUT", path, json)
    fun delete(path: String): String = call("DELETE", path, null)

    private fun call(method: String, path: String, body: String?): String {
        val url = if (path.startsWith("http")) path else BASE + path
        val req = Request.Builder().url(url)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "FoldKI")
            .apply { if (token.isNotBlank()) header("Authorization", "Bearer $token") }
            .method(method, body?.toRequestBody(JSON) ?: if (method == "GET" || method == "DELETE") null else "".toRequestBody(JSON))
            .build()
        client.newCall(req).execute().use { r ->
            // Logs/downloads: GitHub redirects to a signed URL that must be fetched without the token.
            if (r.code in 300..399) {
                val loc = r.header("Location") ?: throw HttpError(r.code, "Weiterleitung ohne Ziel")
                return plainGet(loc)
            }
            val text = r.body?.string().orEmpty()
            if (r.code >= 400) throw HttpError(r.code, text)
            return text
        }
    }

    private fun plainGet(url: String): String {
        val req = Request.Builder().url(url).header("User-Agent", "FoldKI").build()
        client.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (r.code >= 400) throw HttpError(r.code, text)
            return text
        }
    }

    companion object {
        const val BASE = "https://api.github.com"
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val client = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false)
            .build()

        fun q(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

        /** Path inside a repo for the contents API: every segment encoded, slashes kept. */
        fun repoPath(p: String): String = p.trim('/').split('/').filter { it.isNotEmpty() }.joinToString("/") { q(it) }
    }
}
