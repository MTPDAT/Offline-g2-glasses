package com.example.g2glasses.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WhisperModelSelectorTest {

    private val gb = 1024L * 1024 * 1024
    private val all = WhisperModel.entries.map { it.fileName }.toSet()

    @Test
    fun noModelFiles_selectsNothing() {
        assertNull(WhisperModelSelector.select(setOf("silero_vad.onnx", "model.txt"), 12 * gb))
    }

    @Test
    fun preferredModel_winsWhenPresentAndItFits() {
        assertEquals(WhisperModel.BASE, WhisperModelSelector.select(all, 12 * gb, preferred = WhisperModel.BASE))
    }

    @Test
    fun withoutPreference_largestModelThatFitsIsPicked() {
        assertEquals(WhisperModel.LARGE_V3_TURBO, WhisperModelSelector.select(all, 12 * gb))
        assertEquals(WhisperModel.SMALL, WhisperModelSelector.select(all, 6 * gb))
        assertEquals(WhisperModel.BASE, WhisperModelSelector.select(all, 3 * gb))
    }

    @Test
    fun preferredModelTooBigForRam_fallsBackToTheLargestThatFits() {
        val files = setOf(WhisperModel.BASE.fileName, WhisperModel.LARGE_V3_TURBO.fileName)
        assertEquals(WhisperModel.BASE, WhisperModelSelector.select(files, 4 * gb, preferred = WhisperModel.LARGE_V3_TURBO))
    }

    @Test
    fun preferredModelMissing_usesWhatIsThere() {
        val files = setOf(WhisperModel.TINY.fileName)
        assertEquals(WhisperModel.TINY, WhisperModelSelector.select(files, 12 * gb, preferred = WhisperModel.SMALL))
    }

    @Test
    fun nothingFits_smallestPresentIsStillUsed() {
        val files = setOf(WhisperModel.SMALL.fileName, WhisperModel.LARGE_V3_TURBO.fileName)
        assertEquals(WhisperModel.SMALL, WhisperModelSelector.select(files, 1 * gb))
    }

    @Test
    fun modelTxt_idsParseLeniently() {
        assertEquals(WhisperModel.SMALL, WhisperModel.fromId("small\n"))
        assertEquals(WhisperModel.LARGE_V3_TURBO, WhisperModel.fromId("  Large-V3-Turbo "))
        assertNull(WhisperModel.fromId("medium"))
        assertNull(WhisperModel.fromId(null))
    }

    @Test
    fun modelsAreListedSmallestFirst() {
        // select() relies on this order for "largest that fits" and "smallest present".
        val sizes = WhisperModel.entries.map { it.sizeMb }
        assertEquals(sizes.sorted(), sizes)
        val ram = WhisperModel.entries.map { it.minRamGb }
        assertEquals(ram.sorted(), ram)
    }

    @Test
    fun fileNamesMatchTheFetchScripts() {
        // scripts/fetch_whisper.{sh,ps1} download these exact names from huggingface.co/ggerganov/whisper.cpp.
        assertEquals(
            listOf("ggml-tiny-q5_1.bin", "ggml-base-q5_1.bin", "ggml-small-q5_1.bin", "ggml-large-v3-turbo-q5_0.bin"),
            WhisperModel.entries.map { it.fileName },
        )
    }
}
