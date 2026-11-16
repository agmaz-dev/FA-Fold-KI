package de.foldki.app.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LetterLayoutTest {
    @Test fun readsAssembledLetter() {
        val text = LetterAnalysis.assemble(
            "Betreff: Widerspruch\n\nSehr geehrte Damen und Herren,\n\nich widerspreche.\n\nMit freundlichen Grüßen",
            sender = "Paula Fischer\nLindenallee 18\n14612 Falkensee",
            recipient = "HEK\nMusterallee 86-90\n20095 Hamburg",
            placeDate = "Falkensee, 16.11.2026", name = "Paula Fischer",
            refs = listOf("Ihr Schreiben vom: 03.11.2026", "Ihr Zeichen: 4711230"),
        )
        val l = LetterLayout.parse(text)!!
        assertEquals("Paula Fischer\nLindenallee 18\n14612 Falkensee", l.sender)
        assertTrue(l.recipient.startsWith("HEK"))
        assertEquals("03.11.2026", l.theirDate)
        assertEquals("4711230", l.reference)
        assertEquals("Falkensee, 16.11.2026", l.placeDate)
        assertEquals("Widerspruch", l.subject)
        assertEquals("Paula Fischer", l.signerName)
        assertTrue(l.body.startsWith("Sehr geehrte") && l.body.endsWith("Mit freundlichen Grüßen"))
    }

    @Test fun plainTextIsNoLetter() {
        assertNull(LetterLayout.parse("Einkaufsliste\n\nMilch, Brot"))
    }
}
