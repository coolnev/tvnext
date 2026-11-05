package com.tvnext.app.core.app

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import com.tvnext.app.core.common.log.Logger

/**
 * Android renders the PNG logo from the APP module's `assets/` folder.
 *
 * Compose Multiplatform resources (`commonMain`/`androidMain/composeResources`)
 * and the library `res` are NOT packaged into the APK by this KMP-library setup
 * (a MissingResourceException at the first frame crashed the app before), so the
 * PNG lives in `apps:android/src/main/assets/logo.png` — AGP reliably bundles
 * assets, and the activity context can read them at runtime.
 */
@Composable
actual fun AppLogo(modifier: Modifier) {
    val context = LocalContext.current
    val bitmap by remember {
        mutableStateOf(
            runCatching {
                context.assets.open("logo.png").use { stream ->
                    BitmapFactory.decodeStream(stream)?.asImageBitmap()
                }
            }
                .onFailure { e -> Logger.e("AppLogo", "Failed to load logo.png from assets", e) }
                .getOrNull(),
        )
    }

    val current = bitmap
    if (current != null) {
        Image(
            painter = BitmapPainter(current),
            contentDescription = "TVNext",
            contentScale = ContentScale.Fit,
            modifier = modifier,
        )
    }
}
