package de.foldki.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalGitHubAgentTest {
    @Test fun readsToolCall() {
        val c = LocalGitHubAgent.parseCall("Ich schaue nach.\nWERKZEUG: {\"name\":\"read_file\",\"owner\":\"a\",\"repo\":\"b\",\"path\":\"x {y}.md\"} danach")!!
        assertEquals("read_file", c.getString("name"))
        assertEquals("x {y}.md", c.getString("path"))
    }

    @Test fun noCallMeansDone() {
        assertNull(LocalGitHubAgent.parseCall("Fertig. Hier ist die Zusammenfassung."))
        assertNull(LocalGitHubAgent.parseCall("WERKZEUG: {kaputt"))
    }

    @Test fun systemListsTools() {
        val s = LocalGitHubAgent.system("Du bist hilfreich.")
        assertTrue(s.contains("WERKZEUG:") && s.contains("write_files") && s.contains("list_my_repositories"))
    }
}
