package de.foldki.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HfModelsTest {
    private val repo = "TeichAI/Qwen3-4B-Thinking-2507-Claude-4.5-Opus-High-Reasoning-Distill-GGUF"

    @Test fun repoFromModelPage() {
        assertEquals(repo, HfModels.repoFromUrl("https://huggingface.co/$repo"))
        assertEquals(repo, HfModels.repoFromUrl("https://huggingface.co/$repo/tree/main"))
        assertNull(HfModels.repoFromUrl("https://huggingface.co/models?library=gguf"))
        assertNull(HfModels.repoFromUrl("https://huggingface.co/spaces/a/b"))
        assertNull(HfModels.repoFromUrl("https://huggingface.co/TeichAI"))
        assertNull(HfModels.repoFromUrl("https://example.com/a/b"))
    }

    @Test fun fileFromResolveUrl() {
        assertEquals(HfModels.SUGGESTED_PATH, HfModels.fileFromUrl(HfModels.SUGGESTED.url))
        assertEquals("sub/x.gguf", HfModels.fileFromUrl("https://huggingface.co/a/b/blob/main/sub/x.gguf"))
        assertNull(HfModels.fileFromUrl("https://huggingface.co/a/b/blob/main/README.md"))
    }

    @Test fun suggestedUrlMatchesJarvisEntry() {
        assertEquals(HfModels.MODELS[0][4], HfModels.SUGGESTED.url)
        assertEquals("qwen3-4b-thinking", HfModels.MODELS[0][0])
        assertEquals(2_497_281_056L, HfModels.SUGGESTED_SIZE)
    }

    @Test fun parseTreeAndRecommend() {
        val json = """[
          {"type":"file","path":"README.md","size":100},
          {"type":"file","path":"m.Q8_0.gguf","size":4280000000,"lfs":{"oid":"${"a".repeat(64)}","size":4280000000}},
          {"type":"file","path":"m.q4_k_m.gguf","size":2497281056,"lfs":{"oid":"${"b".repeat(64)}","size":2497281056}},
          {"type":"file","path":"m-Q2_K.gguf","size":1600000000},
          {"type":"file","path":"mmproj-F16.gguf","size":800000000},
          {"type":"file","path":"big/m-Q4_K_M-00001-of-00002.gguf","size":5000000000},
          {"type":"directory","path":"big"}
        ]"""
        val files = HfModels.parseTree("a/b", json)
        assertEquals(5, files.size)
        assertEquals("m.q4_k_m.gguf", files.first().path)
        assertEquals("b".repeat(64), files.first().sha256)
        assertEquals("m.q4_k_m.gguf", HfModels.recommend(files)?.path)
        assertTrue(files.first { it.path.contains("00001-of") }.isSplit)
        assertTrue(files.first { it.path.startsWith("mmproj") }.isProjector)
        assertFalse(files.first().isSplit)
        assertEquals("Q4_K_M", files.first().quant)
    }

    @Test fun ramWarning() {
        val fold7 = 12_000_000_000L
        assertFalse(HfModels.tooBigFor(fold7, HfModels.SUGGESTED_SIZE))
        assertTrue(HfModels.tooBigFor(fold7, 9_000_000_000L))
    }

    @Test fun displayNames() {
        assertEquals("Qwen3 4B Thinking (Opus-Destillat)", HfModels.displayName(HfModels.SUGGESTED_PATH))
        assertEquals("Llama-3.2-1B-Instruct · Q8_0", HfModels.displayName("Llama-3.2-1B-Instruct-Q8_0.gguf"))
        assertEquals("eigenes-modell", HfModels.displayName("eigenes-modell.gguf"))
    }

    @Test fun searchUrl() {
        assertEquals("https://huggingface.co/models?library=gguf&sort=trending&search=Qwen", HfModels.searchUrl("Qwen"))
    }
}
