package de.foldki.app.github

import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

/**
 * Everything Claude may do on GitHub with the user's token. Reading runs at once; every change
 * ([Spec.write] = true) is shown to the user first and only runs after "Freigeben".
 */
class GitHubTools(private val api: GitHubApi) {

    data class Param(
        val name: String, val type: String, val description: String,
        val required: Boolean = true, val items: Map<String, Any>? = null,
    )

    data class Spec(val name: String, val description: String, val params: List<Param>, val write: Boolean = false) {
        /** JSON schema for the Claude tool definition. */
        fun properties(): Map<String, Any> = params.associate { p ->
            p.name to buildMap<String, Any> {
                put("type", p.type); put("description", p.description)
                p.items?.let { put("items", it) }
            }
        }
        fun required(): List<String> = params.filter { it.required }.map { it.name }
    }

    companion object {
        private val OWNER = Param("owner", "string", "Besitzer (Nutzer oder Organisation)")
        private val REPO = Param("repo", "string", "Name des Repositorys")
        private fun opt(name: String, type: String, d: String) = Param(name, type, d, required = false)

        val SPECS: List<Spec> = listOf(
            Spec("whoami", "Zeigt das GitHub-Konto, zu dem der Schlüssel gehört.", emptyList()),
            Spec("search_repositories", "Sucht öffentliche und eigene Repositorys (GitHub-Suchsyntax, z. B. 'pdf android language:kotlin stars:>50').",
                listOf(Param("query", "string", "Suchanfrage"))),
            Spec("search_code", "Sucht Code auf GitHub (Bausteine finden), z. B. 'PdfRenderer language:kotlin' oder 'repo:owner/name Funktion'.",
                listOf(Param("query", "string", "Suchanfrage"))),
            Spec("search_issues", "Sucht Issues und Pull Requests (z. B. 'repo:owner/name is:open bug').",
                listOf(Param("query", "string", "Suchanfrage"))),
            Spec("list_my_repositories", "Listet die Repositorys des Nutzers (zuletzt geändert zuerst).", emptyList()),
            Spec("get_repository", "Infos zu einem Repository (Beschreibung, Standard-Branch, Sprache).", listOf(OWNER, REPO)),
            Spec("list_directory", "Listet Dateien und Ordner in einem Pfad des Repositorys.",
                listOf(OWNER, REPO, opt("path", "string", "Ordner, leer = Hauptordner"), opt("ref", "string", "Branch, Tag oder Commit"))),
            Spec("read_file", "Liest eine Textdatei aus einem Repository.",
                listOf(OWNER, REPO, Param("path", "string", "Dateipfad"), opt("ref", "string", "Branch, Tag oder Commit"))),
            Spec("list_branches", "Listet die Branches.", listOf(OWNER, REPO)),
            Spec("list_commits", "Letzte Commits eines Branches.", listOf(OWNER, REPO, opt("branch", "string", "Branch, leer = Standard"))),
            Spec("list_issues", "Issues eines Repositorys.", listOf(OWNER, REPO, opt("state", "string", "open, closed oder all"))),
            Spec("list_pull_requests", "Pull Requests eines Repositorys.", listOf(OWNER, REPO, opt("state", "string", "open, closed oder all"))),
            Spec("get_pull_request", "Ein Pull Request mit geänderten Dateien und Diff-Ausschnitten.",
                listOf(OWNER, REPO, Param("number", "integer", "Nummer"))),
            Spec("list_workflows", "GitHub-Actions-Workflows eines Repositorys.", listOf(OWNER, REPO)),
            Spec("list_workflow_runs", "Letzte Läufe von GitHub Actions (Builds) mit Status und Ergebnis.",
                listOf(OWNER, REPO, opt("branch", "string", "Nur dieser Branch"))),
            Spec("get_run_jobs", "Jobs und fehlgeschlagene Schritte eines Workflow-Laufs.",
                listOf(OWNER, REPO, Param("run_id", "integer", "ID des Laufs"))),
            Spec("get_job_log", "Ende des Protokolls eines Jobs (um Build-Fehler zu finden).",
                listOf(OWNER, REPO, Param("job_id", "integer", "ID des Jobs"))),
            Spec("list_releases", "Releases mit Download-Links (z. B. fertige APKs).", listOf(OWNER, REPO)),
            Spec("wait", "Wartet, z. B. bis ein Build fertig ist (höchstens 120 Sekunden pro Aufruf).",
                listOf(Param("seconds", "integer", "Sekunden, 10 bis 120"))),

            Spec("create_repository", "Legt ein neues Repository im Konto des Nutzers an (mit erstem Commit).",
                listOf(Param("name", "string", "Name"), opt("description", "string", "Beschreibung"),
                    opt("private", "boolean", "privat (Standard: ja)")), write = true),
            Spec("fork_repository", "Kopiert (forkt) ein fremdes Repository ins Konto des Nutzers.", listOf(OWNER, REPO), write = true),
            Spec("create_branch", "Legt einen neuen Branch an.",
                listOf(OWNER, REPO, Param("branch", "string", "Name des neuen Branches"), opt("from", "string", "Ausgangs-Branch, leer = Standard")),
                write = true),
            Spec("write_files",
                "Schreibt, ändert oder löscht mehrere Dateien in EINEM Commit auf einem Branch. Jede Datei hat 'path' und entweder " +
                    "'content' (vollständiger neuer Text) oder 'copy_from' ('owner/repo/pfad@ref', kopiert auch Binärdateien wie gradle-wrapper.jar).",
                listOf(OWNER, REPO, Param("branch", "string", "Branch"), Param("message", "string", "Commit-Nachricht"),
                    Param("files", "array", "Dateien", required = false, items = mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "path" to mapOf("type" to "string"),
                            "content" to mapOf("type" to "string"),
                            "copy_from" to mapOf("type" to "string"),
                        ),
                        "required" to listOf("path"),
                    )),
                    Param("delete_paths", "array", "Zu löschende Dateien", required = false, items = mapOf("type" to "string"))),
                write = true),
            Spec("create_pull_request", "Erstellt einen Pull Request.",
                listOf(OWNER, REPO, Param("title", "string", "Titel"), Param("head", "string", "Branch mit den Änderungen"),
                    Param("base", "string", "Ziel-Branch"), opt("body", "string", "Beschreibung")), write = true),
            Spec("merge_pull_request", "Führt einen Pull Request zusammen (Standard: squash).",
                listOf(OWNER, REPO, Param("number", "integer", "Nummer"), opt("method", "string", "merge, squash oder rebase")), write = true),
            Spec("create_issue", "Erstellt ein Issue.", listOf(OWNER, REPO, Param("title", "string", "Titel"), opt("body", "string", "Text")), write = true),
            Spec("comment", "Schreibt einen Kommentar unter ein Issue oder einen Pull Request.",
                listOf(OWNER, REPO, Param("number", "integer", "Nummer"), Param("body", "string", "Text")), write = true),
            Spec("run_workflow", "Startet einen Workflow von Hand (workflow_dispatch), z. B. um eine APK zu bauen.",
                listOf(OWNER, REPO, Param("workflow", "string", "Datei, z. B. build-apk.yml"), opt("ref", "string", "Branch, leer = Standard")),
                write = true),
            Spec("rerun_workflow", "Startet einen fehlgeschlagenen Workflow-Lauf neu.",
                listOf(OWNER, REPO, Param("run_id", "integer", "ID des Laufs")), write = true),
        )

        val WRITE_NAMES: Set<String> = SPECS.filter { it.write }.map { it.name }.toSet()

        fun cap(s: String, n: Int) = if (s.length <= n) s else s.take(n) + "\n… (gekürzt, ${s.length - n} Zeichen mehr)"

        /** "owner/repo/path/to/file@ref" -> parts; null if malformed. */
        fun parseCopyFrom(s: String): List<String>? {
            val ref = s.substringAfterLast('@', "").takeIf { s.contains('@') }.orEmpty()
            val main = if (s.contains('@')) s.substringBeforeLast('@') else s
            val parts = main.trim('/').split('/')
            if (parts.size < 3) return null
            return listOf(parts[0], parts[1], parts.drop(2).joinToString("/"), ref)
        }

        /** What the user sees before a change runs (German). */
        fun describe(name: String, i: JSONObject): Pair<String, String> {
            val r = "${i.optString("owner")}/${i.optString("repo")}"
            return when (name) {
                "create_repository" -> "Neues Repository anlegen" to
                    "Name: ${i.optString("name")}\n${if (i.optBoolean("private", true)) "Privat" else "Öffentlich"}\n${i.optString("description")}"
                "fork_repository" -> "Repository forken" to "$r in dein Konto kopieren"
                "create_branch" -> "Branch anlegen" to "$r: ${i.optString("branch")} (von ${i.optString("from").ifBlank { "Standard-Branch" }})"
                "write_files" -> {
                    val sb = StringBuilder("$r · Branch ${i.optString("branch")}\nCommit: ${i.optString("message")}\n")
                    val files = i.optJSONArray("files") ?: JSONArray()
                    for (k in 0 until files.length()) {
                        val f = files.optJSONObject(k) ?: continue
                        sb.append("\n▶ ").append(f.optString("path"))
                        if (f.has("copy_from")) sb.append("  (Kopie von ").append(f.optString("copy_from")).append(")\n")
                        else sb.append("\n").append(cap(f.optString("content"), 1500)).append("\n")
                    }
                    val del = i.optJSONArray("delete_paths") ?: JSONArray()
                    for (k in 0 until del.length()) sb.append("\n✕ löschen: ").append(del.optString(k))
                    "Dateien schreiben (Commit)" to cap(sb.toString(), 12000)
                }
                "create_pull_request" -> "Pull Request erstellen" to
                    "$r: ${i.optString("head")} → ${i.optString("base")}\n${i.optString("title")}\n\n${cap(i.optString("body"), 2000)}"
                "merge_pull_request" -> "Pull Request zusammenführen" to "$r #${i.optInt("number")} (${i.optString("method").ifBlank { "squash" }})"
                "create_issue" -> "Issue erstellen" to "$r: ${i.optString("title")}\n\n${cap(i.optString("body"), 2000)}"
                "comment" -> "Kommentar schreiben" to "$r #${i.optInt("number")}\n\n${cap(i.optString("body"), 2000)}"
                "run_workflow" -> "Workflow starten" to "$r: ${i.optString("workflow")} auf ${i.optString("ref").ifBlank { "Standard-Branch" }}"
                "rerun_workflow" -> "Workflow neu starten" to "$r: Lauf ${i.optLong("run_id")}"
                else -> name to i.toString(2)
            }
        }

        /** Short progress line for the chat. */
        fun progress(name: String, i: JSONObject): String {
            val r = "${i.optString("owner")}/${i.optString("repo")}"
            return when (name) {
                "search_repositories", "search_code", "search_issues" -> "🔎 Suche: ${i.optString("query")}"
                "read_file" -> "📄 Lese $r/${i.optString("path")}"
                "list_directory" -> "📁 $r/${i.optString("path")}"
                "list_workflow_runs", "get_run_jobs", "get_job_log" -> "⚙️ Build-Status $r"
                "wait" -> "⏳ Warte ${i.optInt("seconds")} s"
                "write_files" -> "✍️ Commit in $r"
                else -> "• ${name.replace('_', ' ')}" + if (i.has("repo")) " $r" else ""
            }
        }
    }

    fun run(name: String, i: JSONObject): String {
        val o = i.optString("owner"); val r = i.optString("repo")
        val base = "/repos/${GitHubApi.q(o)}/${GitHubApi.q(r)}"
        return when (name) {
            "whoami" -> JSONObject(api.get("/user")).let { "Login: ${it.optString("login")}\nName: ${it.optString("name")}" }
            "search_repositories" -> items(api.get("/search/repositories?per_page=10&q=" + GitHubApi.q(i.optString("query")))) {
                "${it.optString("full_name")} ★${it.optInt("stargazers_count")} ${it.optString("language")} – ${it.optString("description").take(150)}"
            }
            "search_code" -> items(api.get("/search/code?per_page=15&q=" + GitHubApi.q(i.optString("query")))) {
                "${it.optJSONObject("repository")?.optString("full_name")}/${it.optString("path")}"
            }
            "search_issues" -> items(api.get("/search/issues?per_page=10&q=" + GitHubApi.q(i.optString("query")))) {
                "#${it.optInt("number")} [${it.optString("state")}] ${it.optString("title")} – ${it.optString("html_url")}"
            }
            "list_my_repositories" -> list(api.get("/user/repos?sort=updated&per_page=30")) {
                "${it.optString("full_name")}${if (it.optBoolean("private")) " (privat)" else ""} · ${it.optString("default_branch")}"
            }
            "get_repository" -> JSONObject(api.get(base)).let {
                "${it.optString("full_name")}\n${it.optString("description")}\nStandard-Branch: ${it.optString("default_branch")}\n" +
                    "Sprache: ${it.optString("language")}\n${if (it.optBoolean("private")) "privat" else "öffentlich"} · ${it.optString("html_url")}"
            }
            "list_directory" -> {
                val t = api.get("$base/contents/${GitHubApi.repoPath(i.optString("path"))}${ref(i.optString("ref"))}")
                if (t.trimStart().startsWith("[")) list(t) { "${if (it.optString("type") == "dir") "📁" else "📄"} ${it.optString("path")}" }
                else "Das ist eine Datei – mit read_file lesen."
            }
            "read_file" -> {
                val j = JSONObject(api.get("$base/contents/${GitHubApi.repoPath(i.optString("path"))}${ref(i.optString("ref"))}"))
                val text = if (j.optString("content").isNotBlank()) String(Base64.getMimeDecoder().decode(j.optString("content")))
                    else api.get(j.optString("download_url"))
                cap(text, 60_000)
            }
            "list_branches" -> list(api.get("$base/branches?per_page=50")) { it.optString("name") }
            "list_commits" -> list(api.get("$base/commits?per_page=15" + i.optString("branch").let { if (it.isBlank()) "" else "&sha=" + GitHubApi.q(it) })) {
                val c = it.optJSONObject("commit")
                "${it.optString("sha").take(7)} ${c?.optString("message")?.lines()?.firstOrNull()} (${c?.optJSONObject("author")?.optString("date")})"
            }
            "list_issues" -> list(api.get("$base/issues?per_page=20&state=" + i.optString("state").ifBlank { "open" })) {
                "#${it.optInt("number")} ${if (it.has("pull_request")) "[PR] " else ""}${it.optString("title")}"
            }
            "list_pull_requests" -> list(api.get("$base/pulls?per_page=20&state=" + i.optString("state").ifBlank { "open" })) {
                "#${it.optInt("number")} ${it.optString("title")} (${it.optJSONObject("head")?.optString("ref")} → ${it.optJSONObject("base")?.optString("ref")})"
            }
            "get_pull_request" -> {
                val n = i.optInt("number")
                val p = JSONObject(api.get("$base/pulls/$n"))
                val files = list(api.get("$base/pulls/$n/files?per_page=50")) {
                    "${it.optString("filename")} (+${it.optInt("additions")}/-${it.optInt("deletions")})\n${cap(it.optString("patch"), 1500)}"
                }
                cap("#$n ${p.optString("title")} [${p.optString("state")}] ${p.optJSONObject("head")?.optString("ref")} → " +
                    "${p.optJSONObject("base")?.optString("ref")}\n${p.optString("body").take(2000)}\n\n$files", 30_000)
            }
            "list_workflows" -> JSONObject(api.get("$base/actions/workflows")).optJSONArray("workflows").lines {
                "${it.optString("name")} – ${it.optString("path")} (${it.optString("state")})"
            }
            "list_workflow_runs" -> JSONObject(api.get("$base/actions/runs?per_page=8" +
                i.optString("branch").let { if (it.isBlank()) "" else "&branch=" + GitHubApi.q(it) })).optJSONArray("workflow_runs").lines {
                "Lauf ${it.optLong("id")} · ${it.optString("name")} · ${it.optString("head_branch")} · ${it.optString("status")}" +
                    "/${it.optString("conclusion")} · ${it.optString("created_at")}"
            }
            "get_run_jobs" -> JSONObject(api.get("$base/actions/runs/${i.optLong("run_id")}/jobs")).optJSONArray("jobs").lines { j ->
                val failed = j.optJSONArray("steps").lines { s -> if (s.optString("conclusion") == "failure") "  ✕ ${s.optString("name")}" else "" }
                "Job ${j.optLong("id")} ${j.optString("name")}: ${j.optString("status")}/${j.optString("conclusion")}\n$failed".trimEnd()
            }
            "get_job_log" -> api.get("$base/actions/jobs/${i.optLong("job_id")}/logs").let { it.takeLast(12_000) }
            "list_releases" -> list(api.get("$base/releases?per_page=5")) { rel ->
                "${rel.optString("name").ifBlank { rel.optString("tag_name") }} (${rel.optString("published_at")})\n" +
                    rel.optJSONArray("assets").lines { a -> "  ${a.optString("name")}: ${a.optString("browser_download_url")}" }
            }

            "create_repository" -> JSONObject(api.post("/user/repos", JSONObject()
                .put("name", i.optString("name")).put("description", i.optString("description"))
                .put("private", i.optBoolean("private", true)).put("auto_init", true).toString()))
                .let { "Angelegt: ${it.optString("full_name")} (${it.optString("html_url")}), Branch ${it.optString("default_branch")}" }
            "fork_repository" -> JSONObject(api.post("$base/forks", "{}")).let { "Fork: ${it.optString("full_name")}" }
            "create_branch" -> {
                val from = i.optString("from").ifBlank { JSONObject(api.get(base)).optString("default_branch") }
                val sha = headSha(base, from)
                api.post("$base/git/refs", JSONObject().put("ref", "refs/heads/" + i.optString("branch")).put("sha", sha).toString())
                "Branch ${i.optString("branch")} angelegt (von $from)"
            }
            "write_files" -> writeFiles(base, i)
            "create_pull_request" -> JSONObject(api.post("$base/pulls", JSONObject().put("title", i.optString("title"))
                .put("head", i.optString("head")).put("base", i.optString("base")).put("body", i.optString("body")).toString()))
                .let { "PR #${it.optInt("number")}: ${it.optString("html_url")}" }
            "merge_pull_request" -> JSONObject(api.put("$base/pulls/${i.optInt("number")}/merge",
                JSONObject().put("merge_method", i.optString("method").ifBlank { "squash" }).toString()))
                .let { if (it.optBoolean("merged")) "Zusammengeführt: ${it.optString("sha").take(7)}" else it.optString("message") }
            "create_issue" -> JSONObject(api.post("$base/issues", JSONObject().put("title", i.optString("title")).put("body", i.optString("body")).toString()))
                .let { "Issue #${it.optInt("number")}: ${it.optString("html_url")}" }
            "comment" -> JSONObject(api.post("$base/issues/${i.optInt("number")}/comments", JSONObject().put("body", i.optString("body")).toString()))
                .let { "Kommentar: ${it.optString("html_url")}" }
            "run_workflow" -> {
                val refName = i.optString("ref").ifBlank { JSONObject(api.get(base)).optString("default_branch") }
                api.post("$base/actions/workflows/${GitHubApi.q(i.optString("workflow"))}/dispatches", JSONObject().put("ref", refName).toString())
                "Workflow gestartet – mit list_workflow_runs den Stand prüfen."
            }
            "rerun_workflow" -> { api.post("$base/actions/runs/${i.optLong("run_id")}/rerun", "{}"); "Neu gestartet." }
            else -> "Unbekanntes Werkzeug: $name"
        }
    }

    private fun headSha(base: String, branch: String): String =
        JSONObject(api.get("$base/git/ref/heads/${GitHubApi.repoPath(branch)}")).getJSONObject("object").getString("sha")

    /** One commit with all files (Git data API): new tree on top of the branch, then move the branch. */
    private fun writeFiles(base: String, i: JSONObject): String {
        val branch = i.optString("branch")
        val parent = headSha(base, branch)
        val baseTree = JSONObject(api.get("$base/git/commits/$parent")).getJSONObject("tree").getString("sha")
        val tree = JSONArray()
        val files = i.optJSONArray("files") ?: JSONArray()
        for (k in 0 until files.length()) {
            val f = files.getJSONObject(k)
            val path = f.getString("path").trim('/')
            val mode = if (path.endsWith("gradlew") || path.endsWith(".sh")) "100755" else "100644"
            val entry = JSONObject().put("path", path).put("mode", mode).put("type", "blob")
            if (f.has("copy_from")) {
                val (so, sr, sp, sref) = parseCopyFrom(f.getString("copy_from")) ?: error("copy_from ungültig: ${f.getString("copy_from")}")
                val src = JSONObject(api.get("/repos/${GitHubApi.q(so)}/${GitHubApi.q(sr)}/contents/${GitHubApi.repoPath(sp)}${ref(sref)}"))
                val b64 = src.optString("content").replace("\n", "")
                require(b64.isNotBlank()) { "Quelle zu groß oder leer: ${f.getString("copy_from")}" }
                val blob = JSONObject(api.post("$base/git/blobs", JSONObject().put("content", b64).put("encoding", "base64").toString()))
                entry.put("sha", blob.getString("sha"))
            } else {
                entry.put("content", f.optString("content"))
            }
            tree.put(entry)
        }
        val del = i.optJSONArray("delete_paths") ?: JSONArray()
        for (k in 0 until del.length()) {
            tree.put(JSONObject().put("path", del.getString(k).trim('/')).put("mode", "100644").put("type", "blob").put("sha", JSONObject.NULL))
        }
        require(tree.length() > 0) { "Keine Dateien angegeben." }
        val newTree = JSONObject(api.post("$base/git/trees", JSONObject().put("base_tree", baseTree).put("tree", tree).toString())).getString("sha")
        val commit = JSONObject(api.post("$base/git/commits", JSONObject().put("message", i.optString("message").ifBlank { "Änderungen von Fold KI" })
            .put("tree", newTree).put("parents", JSONArray().put(parent)).toString())).getString("sha")
        api.patch("$base/git/refs/heads/${GitHubApi.repoPath(branch)}", JSONObject().put("sha", commit).toString())
        return "Commit ${commit.take(7)} auf $branch (${files.length()} Datei(en) geschrieben, ${del.length()} gelöscht)"
    }

    private fun ref(r: String) = if (r.isBlank()) "" else "?ref=" + GitHubApi.q(r)

    private fun items(json: String, f: (JSONObject) -> String): String {
        val j = JSONObject(json)
        return "Treffer: ${j.optInt("total_count")}\n" + j.optJSONArray("items").lines(f)
    }

    private fun list(json: String, f: (JSONObject) -> String): String = JSONArray(json).lines(f)

    private fun JSONArray?.lines(f: (JSONObject) -> String): String {
        if (this == null || length() == 0) return "(nichts)"
        return (0 until length()).mapNotNull { optJSONObject(it)?.let(f) }.filter { it.isNotBlank() }.joinToString("\n")
    }
}
