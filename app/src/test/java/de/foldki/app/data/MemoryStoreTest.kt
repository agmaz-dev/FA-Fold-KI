package de.foldki.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryStoreTest {
    @Test fun explicitMerkDir() {
        assertEquals(listOf("meine Lieblingsfarbe ist Blau"), MemoryStore.factsIn("Merk dir: meine Lieblingsfarbe ist Blau"))
        assertEquals(listOf("ich morgen Spätschicht habe"), MemoryStore.factsIn("bitte merke dir, dass ich morgen Spätschicht habe"))
    }

    @Test fun statementsAboutMe() {
        val f = MemoryStore.factsIn("Hallo, ich heiße Paula und ich wohne in Falkensee.")
        assertTrue(f.any { it.startsWith("ich heiße Paula") })
        assertTrue(f.any { it.startsWith("ich wohne in Falkensee") })
    }

    @Test fun normalQuestionNothing() {
        assertTrue(MemoryStore.factsIn("Was heißt Perşembe auf Deutsch?").isEmpty())
    }

    @Test fun promptBlock() {
        val b = MemoryStore.promptBlock(listOf("Ich heiße Paula"), listOf("Widerspruch Bußgeld"))
        assertTrue(b.contains("- Ich heiße Paula"))
        assertTrue(b.contains("Widerspruch Bußgeld"))
    }
}
