package com.tvnext.app.core.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Verifies the actual-decoder detection from libVLC native log markers,
 * including the AUTO-fallback pinning (a recreated SOFTWARE backend must never
 * be reported as hardware because the released hardware backend still delivers
 * buffered log messages).
 */
class VlcDecoderDetectorTest {

    @Test
    fun d3d11vaMarkerConfirmsHardware() {
        val detector = VlcDecoderDetector()
        detector.onMessage("avcodec decoder: Using D3D11VA (AMD Radeon RX 6800)")
        assertTrue(detector.isHardwareConfirmed())
        assertFalse(detector.isHardwareFailed())
        assertEquals("D3D11VA", detector.decoderName())
    }

    @Test
    fun dxva2MarkerConfirmsHardware() {
        val detector = VlcDecoderDetector()
        detector.onMessage("avcodec decoder: Using DXVA2")
        assertTrue(detector.isHardwareConfirmed())
        assertEquals("DXVA2", detector.decoderName())
    }

    @Test
    fun pixFmtMarkerConfirmsHardware() {
        val detector = VlcDecoderDetector()
        detector.onMessage("Reinit context to 3840x2160, pix_fmt: d3d11va_vld")
        assertTrue(detector.isHardwareConfirmed())
        assertEquals("D3D11VA", detector.decoderName())
    }

    @Test
    fun softwareMarkerAloneIsNotAnInstantFailure() {
        // libVLC's log markers are not ordered: "Using software" often arrives
        // BEFORE "Using D3D11VA" on healthy streams (FFmpeg probes codecs).
        // It must NOT instantly fail HARDWARE mode — the watchdog's
        // confirmation-timeout covers a real "never came up" case.
        val detector = VlcDecoderDetector()
        detector.onMessage("avcodec decoder: Using software")
        assertFalse(detector.isHardwareConfirmed())
        assertFalse(detector.isHardwareFailed())
    }

    @Test
    fun softwareMarkerBeforeHardwareStillConfirmsHardware() {
        // The realistic race: software marker first, D3D11VA second.
        val detector = VlcDecoderDetector()
        detector.onMessage("avcodec decoder: Using software")
        assertFalse(detector.isHardwareConfirmed())
        detector.onMessage("avcodec decoder: Using D3D11VA")
        assertTrue(detector.isHardwareConfirmed())
        assertFalse(detector.isHardwareFailed())
        assertEquals("D3D11VA", detector.decoderName())
    }

    @Test
    fun initFailureIsHardwareFailed() {
        val detector = VlcDecoderDetector()
        detector.onMessage("direct3d11 error: no supported combination of formats")
        assertTrue(detector.isHardwareFailed())
        assertEquals("unavailable (direct3d11 error: no supported combination of formats)", detector.decoderName())
    }

    @Test
    fun hardwareThenSoftwareStillHardwareConfirmed() {
        // libVLC announced the GPU decoder first; a later software marker is
        // not treated as a failure because hardware was actually used.
        val detector = VlcDecoderDetector()
        detector.onMessage("avcodec decoder: Using D3D11VA")
        detector.onMessage("avcodec decoder: Using software")
        assertTrue(detector.isHardwareConfirmed())
        assertFalse(detector.isHardwareFailed())
        assertEquals("D3D11VA", detector.decoderName())
    }

    @Test
    fun forceSoftwarePinsDetectorToSoftware() {
        val detector = VlcDecoderDetector()
        detector.onMessage("avcodec decoder: Using D3D11VA")
        assertTrue(detector.isHardwareConfirmed())

        // AUTO fallback recreates the backend as SOFTWARE and pins the
        // detector; even a buffered "Using D3D11VA" from the released backend
        // must not flip the diagnostics back to hardware.
        detector.reset()
        detector.forceSoftware()
        detector.onMessage("avcodec decoder: Using D3D11VA")
        detector.onMessage("avcodec decoder: Using D3D11VA (AMD Radeon RX 6800)")
        assertFalse(detector.isHardwareConfirmed())
        assertTrue(detector.isHardwareFailed())
        assertEquals(VlcDecoderDetector.SOFTWARE_LABEL, detector.decoderName())
    }

    @Test
    fun initFailureMarkerAfterForceSoftwareIsIgnored() {
        val detector = VlcDecoderDetector()
        detector.forceSoftware()
        // A late failure marker from the released hardware backend must not
        // poison failureDetail()/decoderName() for the pinned software backend:
        // the label stays software and the failure detail stays the software
        // fallback reason (not the stale "no supported combination" text).
        detector.onMessage("direct3d11 error: no supported combination of formats")
        assertEquals(VlcDecoderDetector.SOFTWARE_LABEL, detector.decoderName())
        assertEquals("libVLC переключился на программный декодер", detector.failureDetail())
    }

    @Test
    fun resetClearsForcedSoftware() {
        val detector = VlcDecoderDetector()
        detector.forceSoftware()
        detector.reset()
        detector.onMessage("avcodec decoder: Using D3D11VA")
        assertTrue(detector.isHardwareConfirmed())
        assertEquals("D3D11VA", detector.decoderName())
    }

    @Test
    fun failureDetailForSoftwareFallback() {
        val detector = VlcDecoderDetector()
        detector.onMessage("avcodec decoder: Using software")
        assertEquals("libVLC переключился на программный декодер", detector.failureDetail())
    }
}
