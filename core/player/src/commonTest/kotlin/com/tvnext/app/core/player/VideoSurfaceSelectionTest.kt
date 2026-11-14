package com.tvnext.app.core.player

import kotlin.test.*

class VideoSurfaceSelectionTest {
    private fun facts(width:Int,height:Int,native:Boolean=false)=PlayerDiagnostics(
        "AUTO","HARDWARE",false,"fixture","fixture",null,"${width}x${height}",null,null,
        videoWidth=width,videoHeight=height,nativeVideoSurfaceRequired=native)

    @Test fun missingFactsBetweenHdChannelsDoNotBounceTheNativeViewType() {
        val selection=VideoSurfaceSelection()
        assertFalse(selection.update(true,true,null))
        assertTrue(selection.update(true,true,facts(1920,1080)))
        repeat(100){assertTrue(selection.update(true,true,null))}
        assertTrue(selection.update(true,true,facts(1280,720)))
        assertTrue(selection.update(true,true,facts(0,0)))
    }
    @Test fun uhdAndHdrSwitchToNativeAndKeepItAcrossMetadataGaps() {
        val selection=VideoSurfaceSelection()
        assertTrue(selection.update(true,true,facts(1920,1080)))
        assertFalse(selection.update(true,true,facts(3840,2160)))
        assertFalse(selection.update(true,true,null))
        assertTrue(selection.update(true,true,facts(1920,1080)))
        assertFalse(selection.update(true,true,facts(1920,1080,true)))
        assertFalse(selection.update(true,true,null))
        assertFalse(selection.update(true,true,facts(0,0,true)))
    }
    @Test fun disablingAnimationImmediatelySelectsNativeEvenWithoutMetadataAndDesktopKeepsItsPolicy() {
        val selection=VideoSurfaceSelection()
        assertTrue(selection.update(true,true,facts(1920,1080)))
        assertFalse(selection.update(false,true,null))
        assertFalse(selection.update(true,true,null))
        assertTrue(selection.update(true,true,facts(1920,1080)))
        assertTrue(selection.update(true,false,facts(3840,2160,true)))
        assertFalse(selection.update(false,false,null))
    }
}
