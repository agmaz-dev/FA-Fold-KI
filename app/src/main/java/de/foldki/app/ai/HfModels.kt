package de.foldki.app.ai

import org.json.JSONArray
import java.net.URLDecoder
import java.net.URLEncoder

/** Hugging Face helpers for the GGUF browser (pure functions, unit-tested). */
object HfModels {
    data class GgufFile(
        val repo: String,
        val path: String,
        val size: Long,
        val sha256: String?,
    ) {
        val fileName: String get() = path.substringAfterLast('/')
        val isSplit: Boolean get() = SPLIT.containsMatchIn(fileName)
        /** Vision projector files (mmproj) are no chat models on their own. */
        val isProjector: Boolean get() = fileName.lowercase().contains("mmproj")
        val quant: String get() = QUANT.find(fileName)?.value?.uppercase() ?: ""
        val url: String get() = resolveUrl(repo, path)
    }

    private val SPLIT = Regex("-\\d{5}-of-\\d{5}\\.gguf$", RegexOption.IGNORE_CASE)
    private val QUANT = Regex("(IQ\\d_[A-Z0-9_]+|Q\\d(_[A-Z0-9]+)*|BF16|F16|F32)", RegexOption.IGNORE_CASE)

    /** First path segments that are HF pages, not "owner/model". */
    private val RESERVED = setOf(
        "models", "datasets", "spaces", "docs", "blog", "pricing", "login", "join", "settings", "organizations",
        "papers", "collections", "tasks", "learn", "posts", "enterprise", "api", "chat", "search", "notifications",
        "new", "new-space", "new-dataset", "inference-endpoints", "huggingchat", "terms-of-service", "privacy", "oauth",
    )

    /**
     * Suggested models in the Jarvis-Android format (Llm.MODELS):
     * id, display name, approx. size in GB, description, URL1, URL2 (same file, no second source exists).
     */
    val MODELS: Array<Array<String>> = arrayOf(
        arrayOf(
            "qwen3-4b-thinking", "Qwen3 4B Thinking (Opus-Destillat)", "2.5",
            "Denkt vor jeder Antwort nach, stark bei Logik und Code. Braucht ca. 4 GB Arbeitsspeicher.",
            "https://huggingface.co/TeichAI/Qwen3-4B-Thinking-2507-Claude-4.5-Opus-High-Reasoning-Distill-GGUF/resolve/main/Qwen3-4B-Thinking-2507-Claude-4.5-Opus-High-Reasoning-Distill.q4_k_m.gguf",
            "https://huggingface.co/TeichAI/Qwen3-4B-Thinking-2507-Claude-4.5-Opus-High-Reasoning-Distill-GGUF/resolve/main/Qwen3-4B-Thinking-2507-Claude-4.5-Opus-High-Reasoning-Distill.q4_k_m.gguf",
        ),
    )

    const val SUGGESTED_REPO = "TeichAI/Qwen3-4B-Thinking-2507-Claude-4.5-Opus-High-Reasoning-Distill-GGUF"
    const val SUGGESTED_PATH = "Qwen3-4B-Thinking-2507-Claude-4.5-Opus-High-Reasoning-Distill.q4_k_m.gguf"
    /** Exact size and SHA-256 from the Hugging Face API (LFS) – checked after the download. */
    const val SUGGESTED_SIZE = 2_497_281_056L
    const val SUGGESTED_SHA256 = "4c37a82759c0140ebb764eab778e71c145c6e474be2ae8d723610dc9a85640da"
    val SUGGESTED = GgufFile(SUGGESTED_REPO, SUGGESTED_PATH, SUGGESTED_SIZE, SUGGESTED_SHA256)
    val SUGGESTED_NAME: String get() = MODELS[0][1]
    val SUGGESTED_INFO: String get() = MODELS[0][3]

    fun searchUrl(query: String): String =
        "https://huggingface.co/models?library=gguf&sort=trending" +
            (if (query.isBlank()) "" else "&search=" + URLEncoder.encode(query.trim(), "UTF-8"))

    fun treeUrl(repo: String): String = "https://huggingface.co/api/models/$repo/tree/main?recursive=true"

    fun resolveUrl(repo: String, path: String): String =
        "https://huggingface.co/$repo/resolve/main/" + path.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

    /** "owner/model" of a model page or file URL on huggingface.co, else null. */
    fun repoFromUrl(url: String): String? {
        val u = runCatching { java.net.URI(url) }.getOrNull() ?: return null
        val host = u.host?.lowercase() ?: return null
        if (host != "huggingface.co" && host != "www.huggingface.co" && host != "hf.co") return null
        val seg = (u.rawPath ?: "").split('/').filter { it.isNotEmpty() }.map { URLDecoder.decode(it, "UTF-8") }
        if (seg.size < 2 || seg[0] in RESERVED) return null
        return seg[0] + "/" + seg[1]
    }

    /** File path inside the repo if the URL points to one .gguf file (…/resolve|blob/<rev>/<path>), else null. */
    fun fileFromUrl(url: String): String? {
        val u = runCatching { java.net.URI(url) }.getOrNull() ?: return null
        val seg = (u.rawPath ?: "").split('/').filter { it.isNotEmpty() }.map { URLDecoder.decode(it, "UTF-8") }
        if (seg.size < 5 || (seg[2] != "resolve" && seg[2] != "blob")) return null
        val path = seg.drop(4).joinToString("/")
        return path.takeIf { it.lowercase().endsWith(".gguf") }
    }

    /** .gguf files from the HF API tree (`/api/models/{id}/tree/main`). */
    fun parseTree(repo: String, json: String): List<GgufFile> {
        val arr = JSONArray(json)
        val out = ArrayList<GgufFile>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("type") != "file") continue
            val path = o.optString("path")
            if (!path.lowercase().endsWith(".gguf")) continue
            val lfs = o.optJSONObject("lfs")
            val size = lfs?.optLong("size", -1L)?.takeIf { it > 0 } ?: o.optLong("size", 0L)
            val sha = lfs?.optString("oid")?.takeIf { it.length == 64 }
            out += GgufFile(repo, path, size, sha)
        }
        return sort(out)
    }

    /** Usable files first, recommended (Q4_K_M) on top, then by size. */
    fun sort(files: List<GgufFile>): List<GgufFile> =
        files.sortedWith(compareBy<GgufFile>({ it.isProjector || it.isSplit }, { !isRecommended(it) }, { it.size }))

    fun isRecommended(f: GgufFile): Boolean = f.quant.equals("Q4_K_M", ignoreCase = true)

    /** Proposal: Q4_K_M if present, else the smallest usable file of at least 4 bits. */
    fun recommend(files: List<GgufFile>): GgufFile? {
        val usable = files.filter { !it.isSplit && !it.isProjector }
        return usable.firstOrNull { isRecommended(it) }
            ?: usable.filter { Regex("Q[4-8]", RegexOption.IGNORE_CASE).containsMatchIn(it.quant) }.minByOrNull { it.size }
            ?: usable.minByOrNull { it.size }
    }

    /** Too big for the phone's RAM? (model + ~1 GB context/app must fit into ~60 % of the RAM) */
    fun tooBigFor(totalRamBytes: Long, fileSize: Long): Boolean = totalRamBytes > 0 && fileSize + 1_000_000_000L > totalRamBytes * 0.6

    /** Display name for a downloaded file: "Qwen3-4B-Thinking-… · Q4_K_M". */
    fun displayName(fileName: String): String {
        if (fileName == SUGGESTED_PATH) return SUGGESTED_NAME
        val base = fileName.removeSuffix(".gguf").removeSuffix(".GGUF")
        val q = QUANT.find(base)?.value
        val name = if (q != null) base.replace(Regex("[._-]?" + Regex.escape(q) + "$", RegexOption.IGNORE_CASE), "") else base
        return if (q != null && name.isNotBlank()) "$name · ${q.uppercase()}" else base
    }

    /** Lists the .gguf files of a model repo via the HF API (network; call off the main thread). */
    fun fetchFiles(repo: String): List<GgufFile> {
        val c = java.net.URL(treeUrl(repo)).openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 30000
        c.setRequestProperty("Accept", "application/json")
        try {
            val code = c.responseCode
            if (code == 401 || code == 403) throw IllegalStateException("Dieses Modell ist geschützt (Anmeldung bei Hugging Face nötig).")
            if (code != 200) throw IllegalStateException("Hugging Face antwortet mit HTTP $code.")
            return parseTree(repo, c.inputStream.bufferedReader().use { it.readText() })
        } finally { c.disconnect() }
    }
}
