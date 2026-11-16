package de.foldki.app.github

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubToolsTest {
    @Test fun specsAreConsistent() {
        val names = GitHubTools.SPECS.map { it.name }
        assertEquals(names.size, names.toSet().size)
        GitHubTools.SPECS.forEach { s -> assertTrue(s.name, s.properties().keys.containsAll(s.required())) }
        assertTrue("write_files" in GitHubTools.WRITE_NAMES && "read_file" !in GitHubTools.WRITE_NAMES)
    }

    @Test fun parsesCopyFrom() {
        assertEquals(listOf("o", "r", "gradle/wrapper/gradle-wrapper.jar", "main"), GitHubTools.parseCopyFrom("o/r/gradle/wrapper/gradle-wrapper.jar@main"))
        assertEquals(listOf("o", "r", "a.txt", ""), GitHubTools.parseCopyFrom("o/r/a.txt"))
        assertNull(GitHubTools.parseCopyFrom("o/r"))
    }

    @Test fun describesCommitForApproval() {
        val i = JSONObject().put("owner", "agmaz-dev").put("repo", "Test").put("branch", "main").put("message", "Start")
            .put("files", JSONArray().put(JSONObject().put("path", "README.md").put("content", "Hallo")))
            .put("delete_paths", JSONArray().put("alt.txt"))
        val (title, details) = GitHubTools.describe("write_files", i)
        assertEquals("Dateien schreiben (Commit)", title)
        assertTrue(details, details.contains("agmaz-dev/Test") && details.contains("README.md") && details.contains("Hallo") && details.contains("löschen: alt.txt"))
    }

    @Test fun encodesRepoPaths() {
        assertEquals("app/src/Main%20Datei.kt", GitHubApi.repoPath("/app/src/Main Datei.kt"))
    }
}
