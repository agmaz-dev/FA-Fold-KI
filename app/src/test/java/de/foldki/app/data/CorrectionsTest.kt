package de.foldki.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CorrectionsTest {
    @Test fun detectsCorrectionSignals() {
        for (m in listOf(
            "falsch", "Das ist falsch.", "Nein, das stimmt nicht", "stimmt so nicht", "Nein, richtig ist 3", "Die richtige Antwort ist 12",
            "Falsch, es sind 3 Äpfel", "das war leider nicht richtig", "Denk bitte nochmal nach", "inkorrekt, probier es nochmal",
        )) assertTrue(m, Corrections.isSignal(m))
    }

    @Test fun ignoresNormalMessages() {
        for (m in listOf(
            "Hallo", "Wie viele Äpfel hat Anna?", "Was ist falsch an diesem Satz?", "Danke, das ist richtig gut", "Schreib mir ein Gedicht",
            "", "x".repeat(500) + " falsch",
        )) assertFalse(m, Corrections.isSignal(m))
    }

    @Test fun rightAnswerGivenOrNot() {
        assertFalse(Corrections.givesAnswer("falsch"))
        assertFalse(Corrections.givesAnswer("Nein, das ist falsch. Versuch es nochmal."))
        assertFalse(Corrections.givesAnswer("stimmt nicht"))
        assertTrue(Corrections.givesAnswer("Falsch, es sind 3 Äpfel"))
        assertTrue(Corrections.givesAnswer("Nein, richtig ist Anna"))
        assertTrue(Corrections.givesAnswer("falsch, die Antwort lautet drei"))
    }

    @Test fun extractionPromptHasAllParts() {
        val p = Corrections.extractionPrompt("Wie viele Äpfel?", "5", "falsch", "Es sind 3 Äpfel.")
        assertTrue(p.contains("Bei der Frage „<kurze Frage>“ ist <falsche Antwort> falsch; richtig ist <richtige Antwort> (<kurzer Grund>)."))
        assertTrue(p.contains("Frage: Wie viele Äpfel?"))
        assertTrue(p.contains("Falsche Antwort: 5"))
        assertTrue(p.contains("Korrektur des Nutzers: falsch"))
        assertTrue(p.contains("Neue Antwort: Es sind 3 Äpfel."))
        assertFalse(Corrections.extractionPrompt("q", "w", "m", "").contains("Neue Antwort"))
    }

    @Test fun extractionOutputMustHaveTheForm() {
        val ok = "Bei der Frage „Wie viele Äpfel?“ ist 5 falsch; richtig ist 3 (zwei wurden gegessen)."
        assertEquals(ok, Corrections.parseExtraction("\n  $ok\nNoch ein Satz"))
        assertNull(Corrections.parseExtraction("KEINE"))
        assertNull(Corrections.parseExtraction(""))
        assertNull(Corrections.parseExtraction("Die Antwort war falsch, richtig ist 3."))
        assertNull(Corrections.parseExtraction("Bei der Frage „x“ ist alles gut."))
        assertNull(Corrections.parseExtraction("Bei der Frage „x“ ist 5 falsch; richtig ist " + "y".repeat(400)))
    }

    @Test fun clipCutsAtWordBoundary() {
        assertEquals("kurz", Corrections.clip("  kurz ", 50))
        val c = Corrections.clip("wort ".repeat(100), 20)
        assertTrue(c.length <= 21 && c.endsWith("…"))
    }

    @Test fun correctionEntryIsRecognised() {
        val e = MemoryEntry("1", "Bei der Frage „x“ ist a falsch; richtig ist b.", MemoryCategory.PUZZLE, true, 1)
        assertTrue(e.isCorrection)
        assertFalse(e.copy(category = MemoryCategory.OTHER).isCorrection)
    }
}
