package de.foldki.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GemmaGitHubAgentTest {
    @Test fun readsToolCall() {
        val c = GemmaGitHubAgent.parseCall("Ich schaue nach.\nWERKZEUG: {\"name\":\"read_file\",\"owner\":\"a\",\"repo\":\"b\",\"path\":\"x {y}.md\"} danach")!!
        assertEquals("read_file", c.getString("name"))
        assertEquals("x {y}.md", c.getString("path"))
    }

    @Test fun noCallMeansDone() {
        assertNull(GemmaGitHubAgent.parseCall("Fertig. Hier ist die Zusammenfassung."))
        assertNull(GemmaGitHubAgent.parseCall("WERKZEUG: {kaputt"))
    }

    @Test fun systemListsTools() {
        val s = GemmaGitHubAgent.system("Du bist hilfreich.")
        assertTrue(s.contains("WERKZEUG:") && s.contains("write_files") && s.contains("list_my_repositories"))
    }
}
