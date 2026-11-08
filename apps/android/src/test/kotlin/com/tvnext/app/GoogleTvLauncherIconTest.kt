package com.tvnext.app

import android.app.Application
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28,35], application = Application::class)
class GoogleTvLauncherIconTest {
    @Test fun googleTvMaskReceivesAnOpaqueDarkBackdropInsteadOfLightArcs() {
        val context = RuntimeEnvironment.getApplication()
        val icon = context.resources.getDrawable(R.mipmap.ic_launcher, context.theme)
        assertTrue("API 26+ must resolve the adaptive launcher icon", icon is AdaptiveIconDrawable)
        val background = (icon as AdaptiveIconDrawable).background
        assertTrue(background is ColorDrawable)
        val color = (background as ColorDrawable).color
        assertEquals(255, Color.alpha(color))
        assertTrue("The circular mask's outer area must be dark", Color.red(color) < 45 && Color.green(color) < 45 && Color.blue(color) < 60)
    }
    @Test fun androidTvKeepsItsRectangularBannerResource() {
        val context = RuntimeEnvironment.getApplication()
        assertEquals(R.drawable.banner, context.applicationInfo.banner)
        val banner = context.resources.getDrawable(R.drawable.banner, context.theme)
        assertTrue(banner is BitmapDrawable)
        assertEquals(320, (banner as BitmapDrawable).bitmap.width)
        assertEquals(180, banner.bitmap.height)
    }
}
