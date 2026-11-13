package com.tvnext.app.feature.channels.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.unit.dp
import org.junit.Test
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class WindowedCategoryWidthUiTest {
    @Test fun categoryPanelFillsTheSameBrowserSlotAsChannelsWithoutMovingVideo()=runDesktopComposeUiTest(width=1100,height=650) {
        var selected:String?=null
        setContent {MaterialTheme(colorScheme=darkColorScheme()) {Row(Modifier.fillMaxSize()) {
            Box(Modifier.width(400.dp).fillMaxHeight().testTag("browser-slot")) {
                ChannelCategoriesPanel(ChannelsState(isLoading=false,categories=listOf("Новости","Фильмы")),{selected=it},expandToVideo=true)
            }
            Spacer(Modifier.width(12.dp))
            Box(Modifier.weight(1f).aspectRatio(16f/9f).testTag("video-slot"))
        }}}
        waitForIdle()
        val browser=onNodeWithTag("browser-slot").fetchSemanticsNode().boundsInRoot
        val categories=onNodeWithTag("windowed-categories-panel").fetchSemanticsNode().boundsInRoot
        val video=onNodeWithTag("video-slot").fetchSemanticsNode().boundsInRoot
        assertEquals(browser.right,categories.right,0.5f)
        assertEquals(400f,categories.width,0.5f)
        assertEquals(412f,video.left,0.5f)
        onNodeWithText("Новости").performClick();assertEquals("Новости",selected)
        assertEquals(video,onNodeWithTag("video-slot").fetchSemanticsNode().boundsInRoot)
        System.getenv("TVNEXT_VALIDATION_DIR")?.let {root->val f=java.io.File(root,"windowed-wide-categories.png");f.parentFile.mkdirs()
            org.jetbrains.skia.Image.makeFromBitmap(onRoot().captureToImage().asSkiaBitmap()).encodeToData()?.use {f.writeBytes(it.bytes)}}
    }
}
