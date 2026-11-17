package de.foldki.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptsTest {
    @Test fun blankSystemMeansNoSystemMessage() {
        assertEquals(listOf("user" to "Hallo"), Prompts.chatMessages("", emptyList(), "", "Hallo"))
        assertEquals(listOf("user" to "Hallo"), Prompts.chatMessages("   \n", emptyList(), "", "Hallo"))
    }

    @Test fun systemIsSentExactlyAsTyped() {
        val sys = "  Antworte auf Türkisch.\n"
        val m = Prompts.chatMessages(sys, listOf("user" to "a", "assistant" to "b"), "", "c")
        assertEquals(listOf("system" to sys, "user" to "a", "assistant" to "b", "user" to "c"), m)
    }

    @Test fun dataComesBeforeTheQuestionInTheUserTurn() {
        val m = Prompts.chatMessages("", emptyList(), "Angehängte Datei x.pdf:\ninhalt\n\n", "Was steht drin?")
        assertEquals(1, m.size)
        assertEquals("user", m[0].first)
        assertEquals("Angehängte Datei x.pdf:\ninhalt\n\nWas steht drin?", m[0].second)
    }

    @Test fun shortQuestionsDoNotThink() {
        assertFalse(Prompts.wantsThinking("Hallo, wie geht's?", false))
        assertFalse(Prompts.wantsThinking("Was heißt Donnerstag auf Türkisch?", false))
        assertFalse(Prompts.wantsThinking("Wann war der 17.11.2026?", false))
    }

    @Test fun attachmentsLongTextCodeAndMathThink() {
        assertTrue(Prompts.wantsThinking("Fasse zusammen", true))
        assertTrue(Prompts.wantsThinking("a".repeat(201), false))
        assertTrue(Prompts.wantsThinking("Schreib mir ein Python Skript", false))
        assertTrue(Prompts.wantsThinking("Was ist 17 * 23?", false))
        assertTrue(Prompts.wantsThinking("Fix das:\n```\nfun main() {}\n```", false))
        assertTrue(Prompts.wantsThinking("Berechne die Wurzel aus 144", false))
    }
}
