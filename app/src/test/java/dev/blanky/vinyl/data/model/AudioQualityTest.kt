package dev.blanky.vinyl.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioQualityTest {

    @Test
    fun `fromName exact match`() {
        assertEquals(AudioQuality.LOSSLESS, AudioQuality.fromName("LOSSLESS"))
        assertEquals(AudioQuality.HI_RES_LOSSLESS, AudioQuality.fromName("HI_RES_LOSSLESS"))
    }

    @Test
    fun `fromName lenient variants`() {
        assertEquals(AudioQuality.HI_RES, AudioQuality.fromName("hi_res"))
        assertEquals(AudioQuality.HI_RES, AudioQuality.fromName("Hi-Res"))
        assertEquals(AudioQuality.HIGH, AudioQuality.fromName(" high "))
    }

    @Test
    fun `fromName unknown falls back to default`() {
        assertEquals(AudioQuality.DEFAULT, AudioQuality.fromName(null))
        assertEquals(AudioQuality.DEFAULT, AudioQuality.fromName("bogus"))
        assertEquals(AudioQuality.DEFAULT, AudioQuality.fromName(""))
    }

    @Test
    fun `tiers are the Monochrome quality names`() {
        assertEquals("LOW", AudioQuality.LOW.tier)
        assertEquals("HIGH", AudioQuality.HIGH.tier)
        assertEquals("LOSSLESS", AudioQuality.LOSSLESS.tier)
        assertEquals("HI_RES", AudioQuality.HI_RES.tier)
        assertEquals("HI_RES_LOSSLESS", AudioQuality.HI_RES_LOSSLESS.tier)
    }

    @Test
    fun `fallbackChain goes down from requested quality`() {
        assertEquals(
            listOf(AudioQuality.HI_RES, AudioQuality.LOSSLESS, AudioQuality.HIGH, AudioQuality.LOW),
            AudioQuality.HI_RES.fallbackChain()
        )
        assertEquals(listOf(AudioQuality.LOW), AudioQuality.LOW.fallbackChain())
        assertEquals(AudioQuality.HI_RES_LOSSLESS, AudioQuality.HI_RES_LOSSLESS.fallbackChain().first())
    }

    @Test
    fun `lowerOf picks the lower quality`() {
        assertEquals(AudioQuality.HIGH, AudioQuality.lowerOf(AudioQuality.HIGH, AudioQuality.LOSSLESS))
        assertEquals(AudioQuality.HIGH, AudioQuality.lowerOf(AudioQuality.LOSSLESS, AudioQuality.HIGH))
    }
}
