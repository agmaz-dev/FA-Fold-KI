package de.foldki.app.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentParserTest {

    private val letter = """
        Max Muster
        Hauptstr. 1
        12345 Berlin

        Betreff: Widerspruch gegen den Bescheid vom 01.11.2026

        Sehr geehrte Damen und Herren,

        hiermit widerspreche ich dem Bescheid.

        Mit freundlichen Grüßen
        Max Muster
    """.trimIndent()

    @Test fun dokumentMarkers() {
        val p = DocumentParser.parse("Hier ist dein Widerspruch.\n---DOKUMENT---\n$letter\n---ENDE---\nIch habe mich auf den Bescheid gestützt.")
        assertEquals(letter, p.document)
        assertEquals("Hier ist dein Widerspruch.", p.before)
        assertEquals("Ich habe mich auf den Bescheid gestützt.", p.after)
        assertTrue(p.complete)
        assertTrue(p.isLetter)
    }

    @Test fun legacyBriefMarkerAndTolerantDashes() {
        val p = DocumentParser.parse("---BRIEF---\n$letter\n---ENDE---")
        assertEquals(letter, p.document)
        val q = DocumentParser.parse("—— Dokument ——\nEinkaufsliste\n- Milch\n- Brot\n—— ende ——")
        assertEquals("Einkaufsliste\n• Milch\n• Brot", q.document)
        assertFalse(q.isLetter)
    }

    @Test fun missingEndeMeansIncomplete() {
        val p = DocumentParser.parse("Klar.\n---DOKUMENT---\nSehr geehrte Damen und Herren,\n\nich kündige")
        assertNotNull(p.document)
        assertFalse(p.complete)
        assertEquals("Sehr geehrte Damen und Herren,\n\nich kündige", p.document)
        assertEquals("", p.after)
    }

    @Test fun noMarkersFormalLetter() {
        val p = DocumentParser.parse("Hier ist der Entwurf:\n\n$letter\n\nSoll ich noch etwas ändern?")
        assertEquals(letter, p.document)
        assertEquals("Hier ist der Entwurf:", p.before)
        assertEquals("Soll ich noch etwas ändern?", p.after)
        assertTrue(p.complete)
    }

    @Test fun noMarkersHalloLetter() {
        val text = "Gerne, hier ist deine Nachricht an Tom:\n\nHallo Tom,\n\nich komme morgen später.\n\nViele Grüße\nPaula\n\nIch hoffe, das passt so."
        val p = DocumentParser.parse(text)
        assertEquals("Hallo Tom,\n\nich komme morgen später.\n\nViele Grüße\nPaula", p.document)
        assertEquals("Gerne, hier ist deine Nachricht an Tom:", p.before)
        assertEquals("Ich hoffe, das passt so.", p.after)
        assertTrue(p.isLetter)
    }

    @Test fun liebeAndLgClosing() {
        val p = DocumentParser.parse("Liebe Anna,\n\ndanke für alles!\n\nLG\nPaula")
        assertEquals("Liebe Anna,\n\ndanke für alles!\n\nLG\nPaula", p.document)
    }

    @Test fun preamblesInsideMarkersAreRemoved() {
        val p = DocumentParser.parse("---DOKUMENT---\nGerne!\nHier ist dein Schreiben:\n\n$letter\n---ENDE---")
        assertEquals(letter, p.document)
        val q = DocumentParser.parse("---DOKUMENT---\nKlar, hier ist die Zusammenfassung:\nDer Bescheid verlangt 200 €.\n---ENDE---")
        assertEquals("Der Bescheid verlangt 200 €.", q.document)
    }

    @Test fun remarksAfterNameInsideMarkersMoveOut() {
        val p = DocumentParser.parse("---DOKUMENT---\n$letter\n\nIch habe das Aktenzeichen ergänzt.\n---ENDE---")
        assertEquals(letter, p.document)
        assertEquals("Ich habe das Aktenzeichen ergänzt.", p.after)
        val q = DocumentParser.parse("---DOKUMENT---\n$letter\n\nAnlage: Bescheid\n---ENDE---")
        assertEquals("$letter\n\nAnlage: Bescheid", q.document)
    }

    @Test fun markdownIsRemoved() {
        val p = DocumentParser.parse("---DOKUMENT---\n## Zusammenfassung\n**Frist:** 30 Tage\n* Punkt *eins*\n- `zwei`\n> Zitat\n---\n1. Nummer\n---ENDE---")
        assertEquals("Zusammenfassung\nFrist: 30 Tage\n• Punkt eins\n• zwei\nZitat\n\n1. Nummer", p.document)
    }

    @Test fun plainAnswerHasNoDocument() {
        val p = DocumentParser.parse("Hallo! Berlin hat etwa 3,8 Millionen Einwohner.")
        assertNull(p.document)
        assertEquals("Hallo! Berlin hat etwa 3,8 Millionen Einwohner.", p.before)
        assertNull(DocumentParser.parse("").document)
    }

    @Test fun spokenSkipsDocument() {
        val s = DocumentParser.spoken("Hier ist dein Brief.\n---DOKUMENT---\n$letter\n---ENDE---\nFertig.")
        assertEquals("Hier ist dein Brief. Fertig.", s)
        assertEquals("Ich habe dir das Dokument in den Chat gelegt.", DocumentParser.spoken("---DOKUMENT---\n$letter\n---ENDE---"))
    }

    @Test fun displayRemovesMarkers() {
        assertEquals("A\n\nB", DocumentParser.display("A\n---DOKUMENT---\nB\n---ENDE---"))
    }
}
