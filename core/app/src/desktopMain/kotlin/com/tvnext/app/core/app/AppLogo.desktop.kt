package com.tvnext.app.core.app

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import org.jetbrains.compose.resources.painterResource
import tvnext.core.app.generated.resources.Res
import tvnext.core.app.generated.resources.logo

/** Desktop renders the vector SVG logo (crisp at any size). */
@Composable
actual fun AppLogo(modifier: Modifier) {
    Image(
        painter = painterResource(Res.drawable.logo),
        contentDescription = "TVNext",
        contentScale = ContentScale.Fit,
        modifier = modifier,
    )
}
