package de.foldki.app.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LetterAnalysisTest {

    private val analysis = """
        KURZ: Die Bußgeldstelle bittet um Angaben zum Fahrer. Die Personalien sind innerhalb einer Woche zu nennen.
        BRIEFDATUM: 03.11.2026
        NOTIZ: -
        FRIST: innerhalb einer Woche nach Zugang
        FRISTDATUM: 10.11.2026
        BETRAG: -
        ABSENDER: Zentrale Bußgeldstelle | Musterstraße 1 | 12345 Musterstadt
        EMAIL: info@bussgeld.example.de
        BEZUG: Aktenzeichen 40297011
        ANSPRECHPARTNER: Frau Beispiel
        VORSCHLAG: Ich war nicht der Fahrer
        VORSCHLAG: Fahrer benennen
        VORSCHLAG: -
    """.trimIndent()

    @Test fun parsesLineFormat() {
        val r = LetterAnalysis.parse(analysis)
        assertEquals("03.11.2026", r.date)
        assertEquals("10.11.2026", r.deadlineDate)
        assertEquals("Zentrale Bußgeldstelle\nMusterstraße 1\n12345 Musterstadt", r.sender)
        assertEquals("Aktenzeichen 40297011", r.reference)
        assertEquals("info@bussgeld.example.de", r.email)
        assertEquals("", r.amount)
        assertEquals(listOf("Ich war nicht der Fahrer", "Fahrer benennen"), r.suggestions)
    }

    @Test fun parsesBrokenJson() {
        val r = LetterAnalysis.parse("""```json {"kurzfassung": "Mahnung", "absender": "Firma X | Weg 2 | 10115 Berlin", "bezug": "Kd-Nr. 77""")
        assertEquals("Mahnung", r.summary)
        assertEquals("Firma X\nWeg 2\n10115 Berlin", r.sender)
    }

    @Test fun fillsAndRemovesPlaceholders() {
        val t = "ich beziehe mich auf Ihr Schreiben vom [Datum des angehängten Schreibens] mit [Aktenzeichen].\n" +
            "[Hier können Sie nun Ihren spezifischen Grund für die Nichtbereitstellung der Daten einfügen.]"
        val out = LetterAnalysis.fixPlaceholders(t, "03.11.2026", "Aktenzeichen 40297011", null, null, null)
        assertTrue(out.contains("vom 03.11.2026"))
        assertTrue(out.contains("mit Aktenzeichen 40297011."))
        assertFalse(out.contains("["))
    }

    @Test fun completesCutEnding() {
        val out = LetterAnalysis.completeEnding("Sehr geehrte Damen und Herren,\n\nich war nicht der Fahrer. Ich bitte um Einstellung des Verf")
        assertTrue(out.endsWith("Ich war nicht der Fahrer.\n\nMit freundlichen Grüßen".substringAfter("Ich war nicht der Fahrer")))
        assertFalse(out.contains("Verf"))
    }

    @Test fun recipientKey() {
        assertEquals("zentralebußgeldstelle", LetterAnalysis.recipientKey("Zentrale Bußgeldstelle\nMusterstraße 1"))
    }
}
