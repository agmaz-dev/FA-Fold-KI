package de.foldki.app.brief

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskExtractorTest {
    private val now = 1_800_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    @Test fun rulesFindTasks() {
        assertTrue(TaskExtractor.isTask("Ich muss noch die Steuererklärung abgeben."))
        assertTrue(TaskExtractor.isTask("Die Frist für den Widerspruch läuft am 30.11. ab."))
        assertTrue(TaskExtractor.isTask("Das Formular bis Montag zur Post bringen."))
        assertTrue(TaskExtractor.isTask("Nicht vergessen: Zahnarzt anrufen."))
        assertTrue(TaskExtractor.isTask("Erinnere mich an die Miete."))
        assertTrue(TaskExtractor.isTask("Rechnung bis spätestens 15. zahlen"))
    }

    @Test fun questionsAndSmallTalkAreNoTasks() {
        assertFalse(TaskExtractor.isTask("Was muss ich bei einem Widerspruch beachten?"))
        assertFalse(TaskExtractor.isTask("Wie lange ist die Frist?"))
        assertFalse(TaskExtractor.isTask("Danke, das ist super."))
        assertFalse(TaskExtractor.isTask("ok"))
    }

    @Test fun onlyLastSevenDaysNewestFirstNoDuplicates() {
        val msgs = listOf(
            TaskExtractor.Message("Ich muss das Auto anmelden.", now - 10 * day),
            TaskExtractor.Message("Ich muss die Miete überweisen. Danke!", now - 2 * day),
            TaskExtractor.Message("Nicht vergessen: Paket abholen", now - day),
            TaskExtractor.Message("ich muss die Miete überweisen", now - 3 * day),
        )
        assertEquals(
            listOf("Nicht vergessen: Paket abholen", "Ich muss die Miete überweisen."),
            TaskExtractor.extract(msgs, now),
        )
    }

    @Test fun maxCount() {
        val msgs = (1..30).map { TaskExtractor.Message("Ich muss Aufgabe $it erledigen.", now - it * 1000L) }
        assertEquals(20, TaskExtractor.extract(msgs, now).size)
    }
}
