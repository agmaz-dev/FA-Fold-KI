package de.foldki.app.ai

import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Internet lookup for Gemini Nano (which cannot browse by itself):
 * Wikipedia articles for background knowledge + Google News RSS for current headlines.
 * Both are free and need no API key. The results are put into Nano's prompt.
 */
object WebLookup {
    private const val UA = "FoldKI (Android chat app)" // no version number that goes stale

    suspend fun search(query: String): String = coroutineScope {
        val wiki = async { runCatching { wikipedia(query) }.getOrDefault("") }
        val news = async { runCatching { news(query) }.getOrDefault("") }
        buildString {
            val w = wiki.await(); val n = news.await()
            if (w.isNotBlank()) append("WIKIPEDIA:\n").append(w).append("\n")
            if (n.isNotBlank()) append("AKTUELLE NACHRICHTEN:\n").append(n)
        }.trim()
    }

    private suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        val c = URL(url).openConnection() as HttpURLConnection
        c.setRequestProperty("User-Agent", UA)
        c.connectTimeout = 8000; c.readTimeout = 8000
        try { c.inputStream.bufferedReader().readText() } finally { c.disconnect() }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private suspend fun wikipedia(query: String): String {
        val search = JSONObject(get(
            "https://de.wikipedia.org/w/api.php?action=query&list=search&format=json&srlimit=3&srsearch=" + enc(query)
        )).getJSONObject("query").getJSONArray("search")
        if (search.length() == 0) return ""
        val titles = (0 until search.length()).map { search.getJSONObject(it).getString("title") }
        val pages = JSONObject(get(
            "https://de.wikipedia.org/w/api.php?action=query&prop=extracts&explaintext=1&exintro=1&exchars=1200&format=json&titles=" +
                enc(titles.joinToString("|"))
        )).getJSONObject("query").getJSONObject("pages")
        return buildString {
            pages.keys().forEach { k ->
                val p = pages.getJSONObject(k)
                val text = p.optString("extract").trim()
                if (text.isNotEmpty()) append("• ").append(p.optString("title")).append(": ").append(text).append("\n")
            }
        }
    }

    private suspend fun news(query: String): String {
        val xml = get("https://news.google.com/rss/search?hl=de&gl=DE&ceid=DE:de&q=" + enc(query))
        val parser = Xml.newPullParser().apply { setInput(xml.reader()) }
        val out = StringBuilder()
        var count = 0
        var inItem = false
        var title = ""; var date = ""
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT && count < 6) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "item" -> { inItem = true; title = ""; date = "" }
                    "title" -> if (inItem) title = parser.nextText()
                    "pubDate" -> if (inItem) date = parser.nextText()
                }
            } else if (event == XmlPullParser.END_TAG && parser.name == "item") {
                inItem = false
                if (title.isNotBlank()) { out.append("• ").append(title).append(" (").append(date.take(16)).append(")\n"); count++ }
            }
            event = parser.next()
        }
        return out.toString()
    }
}
