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

    // ---- prioritization ----

    private fun corr(text: String, created: Long) = MemoryEntry(text, text, MemoryCategory.PUZZLE, true, created, true)

    @Test fun relevantEntriesComeFirstWhenCapIsTight() {
        val apples = corr("Bei der Frage „Äpfel im Korb“ ist 5 falsch; richtig ist 3 (zwei wurden gegessen).", 1)
        val other1 = entry("Ich mag Tee und trinke ihn morgens immer gern am Fenster", true, 2)
        val other2 = entry("Antworte kurz und freundlich in einfachen Worten bitte", true, 3)
        val cap = MemoryStore.HEADING.length + apples.text.length + 3
        val b = MemoryStore.promptBlock(listOf(apples, other1, other2), cap, "Wie viele Äpfel sind im Korb?")
        assertTrue(b.contains("Äpfel im Korb"))
        assertFalse(b.contains("Tee"))
        // without a matching question the newest entry wins
        val c = MemoryStore.promptBlock(listOf(apples, other1, other2), MemoryStore.HEADING.length + other2.text.length + 3, "Hallo")
        assertTrue(c.contains("kurz und freundlich"))
        assertFalse(c.contains("Äpfel"))
    }

    @Test fun correctionsAreRankedAheadOfEqualOverlap() {
        val plain = entry("Korb Äpfel stehen in der Küche", true, 5)
        val c = corr("Bei der Frage „Korb Äpfel“ ist 5 falsch; richtig ist 3.", 1)
        val tight = MemoryStore.HEADING.length + c.text.length + 3
        val b = MemoryStore.promptBlock(listOf(plain, c), tight, "Korb Äpfel")
        assertTrue(b.contains("richtig ist 3"))
        assertFalse(b.contains("Küche"))
    }

    @Test fun inactiveEntriesNeverComeBack() {
        val off = corr("Bei der Frage „x“ ist a falsch; richtig ist b.", 1).copy(active = false)
        assertEquals("", MemoryStore.promptBlock(listOf(off), 1500, "x a b"))
    }

    @Test fun autoFlagIsStoredAndDuplicatesAreNormalised() {
        val st = MapStorage()
        val m = store(st)
        assertTrue(m.add("Ich heiße Paula", auto = true)!!.auto)
        assertNull(m.add("  ich HEIßE   paula. ", auto = false))
        assertTrue(store(st).all().single().auto)
        assertFalse(m.add("Handschriftlich", auto = false)!!.auto)
    }

    // ---- export / import ----

    @Test fun exportImportRoundtrip() {
        val a = store()
        a.add("Schreibe kurz.", MemoryCategory.STYLE)
        val off = a.add("Bei der Frage „Väter“ ist 4 falsch; richtig ist 3.", MemoryCategory.PUZZLE, active = false, auto = true)!!
        a.add("Ich wohne in Berlin", MemoryCategory.PERSONAL, auto = true)
        val json = a.exportJson(autoLearn = false)
        assertTrue(json.contains("\n  ")) // pretty-printed
        assertTrue(json.contains("\"version\": 1"))

        val data = MemoryStore.parseImport(json)
        assertEquals(false, data.autoLearn)
        assertEquals(0, data.skipped)
        val b = store()
        val r = b.merge(data)
        assertEquals(MemoryStore.ImportResult(3, 0), r)
        val got = b.all()
        assertEquals(a.all().map { it.text }, got.map { it.text })
        assertEquals(a.all().map { it.category }, got.map { it.category })
        assertEquals(a.all().map { it.active }, got.map { it.active })
        assertEquals(a.all().map { it.auto }, got.map { it.auto })
        assertEquals(a.all().map { it.created }, got.map { it.created })
        assertFalse(got.first { it.text == off.text }.active)
        assertTrue(got.map { it.id }.toSet().size == 3)
    }

    @Test fun mergeKeepsExistingAndCountsDuplicates() {
        val src = store()
        src.add("Antworte kurz", MemoryCategory.STYLE)
        src.add("Neuer Eintrag", MemoryCategory.OTHER)
        val json = src.exportJson(true)

        val dst = store()
        val mine = dst.add("antworte KURZ.", MemoryCategory.PERSONAL, active = false)!!
        val r = dst.merge(MemoryStore.parseImport(json))
        assertEquals(1, r.imported)
        assertEquals(1, r.duplicates)
        val kept = dst.all().first { it.id == mine.id }
        assertEquals(MemoryCategory.PERSONAL, kept.category) // existing wins
        assertFalse(kept.active)
        assertEquals(2, dst.all().size)
        // second import: everything is a duplicate now
        assertEquals(MemoryStore.ImportResult(0, 2), dst.merge(MemoryStore.parseImport(json)))
    }

    @Test fun invalidFilesAreRejectedAndChangeNothing() {
        val st = MapStorage()
        val m = store(st)
        m.add("Bleibt bestehen")
        val before = st.map["entries"]
        val bad = listOf(
            "", "kein json", "[1,2]", "{}",
            """{"format":"anderes","version":1,"eintraege":[]}""",
            """{"format":"FA-Fold-KI-Gedaechtnis","version":99,"eintraege":[]}""",
            """{"format":"FA-Fold-KI-Gedaechtnis","version":1}""",
            """{"format":"FA-Fold-KI-Gedaechtnis","version":1,"eintraege":[{"text":"x"},{"kategorie":"Persönlich"}]}""",
        )
        for (json in bad) {
            try {
                m.merge(MemoryStore.parseImport(json))
                throw AssertionError("accepted: $json")
            } catch (e: MemoryStore.InvalidImport) {
                assertTrue(e.message!!.isNotBlank())
            }
        }
        assertEquals(before, st.map["entries"])
        assertEquals(1, m.all().size)
    }

    @Test fun importSkipsBrokenEntriesButKeepsGoodOnes() {
        val json = """{"format":"FA-Fold-KI-Gedaechtnis","version":1,"eintraege":[
            {"text":"Gut und lang genug","kategorie":"Schreibstil","aktiv":false,"quelle":"automatisch","erstellt":5},
            {"text":""}, 7, {"text":"Zweiter Eintrag","kategorie":"Unbekannt"}]}"""
        val d = MemoryStore.parseImport(json)
        assertEquals(2, d.entries.size)
        assertEquals(2, d.skipped)
        assertNull(d.autoLearn)
        assertEquals(MemoryCategory.STYLE, d.entries[0].category)
        assertFalse(d.entries[0].active)
        assertTrue(d.entries[0].auto)
        assertEquals(MemoryCategory.OTHER, d.entries[1].category)
        assertTrue(d.entries[1].active)
    }
}
