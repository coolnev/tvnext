package com.tvnext.app.core.player

import kotlin.test.*

class WindowsVideoModeTest {
    @Test fun emulatedGraphicsDoesNotGetReportedAsAHardwareGpu() {
        assertTrue(softwareGpu("ANGLE (Microsoft Basic Render Driver Direct3D11)"))
        assertTrue(softwareGpu("ANGLE (WARP D3D11)"))
        assertTrue(softwareGpu("llvmpipe (LLVM)"))
        assertFalse(softwareGpu("ANGLE (NVIDIA GeForce RTX 4060 Direct3D11)"))
        assertFalse(softwareGpu("ANGLE (Intel UHD Graphics Direct3D11)"))
    }
    @Test fun explicitCopyBackModeSurvivesLegacySettingsUpdates() {
        val player=MpvPlaybackController()
        try {
            player.updateWindowsVideoMode(WindowsVideoMode.HARDWARE_COMPATIBLE)
            player.updateDecodeMode(VideoDecodeMode.AUTO)
            assertEquals(WindowsVideoMode.HARDWARE_COMPATIBLE,player.windowsVideoMode.value)
            assertEquals("auto-copy",player.windowsVideoMode.value.hwdec)
            assertFalse(player.windowsVideoMode.value.gpu)
        }finally{player.release()}
    }
    @Test fun softwareAndGpuAreDistinctFromTheCompatiblePipeline() {
        val player=MpvPlaybackController()
        try {
            player.updateDecodeMode(VideoDecodeMode.SOFTWARE)
            assertEquals(WindowsVideoMode.SOFTWARE,player.windowsVideoMode.value)
            player.updateWindowsVideoMode(WindowsVideoMode.HARDWARE_GPU)
            assertTrue(player.windowsVideoMode.value.gpu)
            assertEquals("d3d11va",player.windowsVideoMode.value.hwdec)
            player.updateDecodeMode(VideoDecodeMode.SOFTWARE)
            assertEquals(VideoDecodeMode.HARDWARE,player.decodeMode.value)
        }finally{player.release()}
    }
}
