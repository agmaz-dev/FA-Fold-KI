package de.foldki.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    // ---- editable memory ----

    private class MapStorage(val map: MutableMap<String, String> = HashMap()) : MemoryStorage {
        override fun get(key: String) = map[key]
        override fun put(key: String, value: String) { map[key] = value }
    }

    private var clock = 1_000L
    private fun store(st: MapStorage = MapStorage()) = MemoryStore(st) { clock++ }

    @Test fun addEditToggleDelete() {
        val st = MapStorage()
        val m = store(st)
        val e = m.add("Schreibe kurz und sachlich.", MemoryCategory.STYLE)!!
        assertEquals(MemoryCategory.STYLE, e.category)
        assertTrue(e.active)
        assertNull(m.add("schreibe kurz und sachlich.")) // duplicate
        assertNull(m.add(" ab ")) // too short

        assertTrue(m.update(e.id, "Antworte immer in einem Satz.", MemoryCategory.OTHER))
        assertEquals("Antworte immer in einem Satz.", m.all().single().text)
        assertEquals(MemoryCategory.OTHER, m.all().single().category)
        assertFalse(m.update("gibt-es-nicht", "Text genug", MemoryCategory.OTHER))
        assertFalse(m.update(e.id, "x", MemoryCategory.OTHER))

        m.setActive(e.id, false)
        assertFalse(m.all().single().active)

        // survives a restart (new store on the same storage)
        val again = store(st).all().single()
        assertEquals(e.id, again.id)
        assertFalse(again.active)
        assertEquals("Antworte immer in einem Satz.", again.text)

        m.remove(e.id)
        assertTrue(m.all().isEmpty())
        assertTrue(store(st).all().isEmpty())
    }

    @Test fun migratesOldFactsAsActivePersoenlich() {
        val st = MapStorage(hashMapOf("facts" to """["Ich heiße Paula","Ich wohne in Falkensee"]"""))
        val list = store(st).all()
        assertEquals(listOf("Ich heiße Paula", "Ich wohne in Falkensee"), list.map { it.text })
        assertTrue(list.all { it.active && it.category == MemoryCategory.PERSONAL })
        assertTrue(list[0].created < list[1].created)
        // migrated once: now stored in the new format, editing works and nothing is migrated again
        val m = store(st)
        m.remove(m.all()[0].id)
        assertEquals(listOf("Ich wohne in Falkensee"), store(st).all().map { it.text })
        assertNotNull(st.map["entries"])
    }

    @Test fun nothingBuiltIn() {
        assertTrue(store().all().isEmpty())
        assertEquals("", MemoryStore.promptBlock(emptyList()))
    }

    private fun entry(text: String, active: Boolean = true, created: Long = 1) =
        MemoryEntry(text, text, MemoryCategory.OTHER, active, created)

    @Test fun promptContainsOnlyActiveEntries() {
        val b = MemoryStore.promptBlock(listOf(entry("A eins", true, 1), entry("B aus", false, 2), entry("C drei", true, 3)))
        assertEquals(MemoryStore.HEADING + "\n- A eins\n- C drei", b)
        assertFalse(b.contains("B aus"))
    }

    @Test fun promptIsCappedNewestFirst() {
        val old = entry("a".repeat(100), true, 1)
        val mid = entry("b".repeat(100), true, 2)
        val new = entry("c".repeat(100), true, 3)
        val cap = MemoryStore.HEADING.length + 2 * 103
        val b = MemoryStore.promptBlock(listOf(old, mid, new), cap)
        assertTrue(b.contains("c".repeat(100)) && b.contains("b".repeat(100)))
        assertFalse(b.contains("a".repeat(100)))
        assertTrue(b.length <= cap)
        assertTrue(MemoryStore.promptBlock(List(50) { entry("x".repeat(100) + it, true, it.toLong()) }).length <= MemoryStore.PROMPT_CHARS)
    }

    @Test fun emptyEverythingMeansNoSystemMessage() {
        assertEquals("", MemoryStore.systemMessage("", emptyList()))
        assertEquals("", MemoryStore.systemMessage("", listOf(entry("aus", false))))
    }

    @Test fun systemMessageIsUserPromptThenActiveEntries() {
        val e = listOf(entry("Duze mich", true))
        assertEquals("Meine Anweisung", MemoryStore.systemMessage("Meine Anweisung", emptyList()))
        assertEquals(MemoryStore.HEADING + "\n- Duze mich", MemoryStore.systemMessage("", e))
        assertEquals("Meine Anweisung\n\n" + MemoryStore.HEADING + "\n- Duze mich", MemoryStore.systemMessage("Meine Anweisung\n", e))
    }

    @Test fun chatWordingOfDetectedFacts() {
        assertEquals("Meine Lieblingsfarbe ist Blau", MemoryStore.fromChat("meine Lieblingsfarbe ist Blau."))
    }

    @Test fun excerptIsFirstSentenceMax200() {
        assertEquals("Das ist der erste Satz.", MemoryStore.excerpt("**Das** ist der erste Satz. Und noch einer."))
        assertEquals("Ohne Punkt am Ende", MemoryStore.excerpt("Ohne Punkt am Ende"))
        val long = MemoryStore.excerpt("wort ".repeat(100))
        assertTrue(long.length <= 201 && long.endsWith("…"))
        assertEquals("", MemoryStore.excerpt("  "))
    }
}
