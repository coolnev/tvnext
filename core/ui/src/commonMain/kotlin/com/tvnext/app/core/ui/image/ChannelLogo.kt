package com.tvnext.app.core.ui.image

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tvnext.app.core.ui.theme.TVNextDimens

/**
 * Provides the [LogoImageLoader] to the composition tree. Set once at the app
 * root by the platform application (desktop/android) from Koin.
 */
val LocalLogoImageLoader = staticCompositionLocalOf<LogoImageLoader?> { null }

/**
 * Composable helper that exposes a [LogoImageLoader] (e.g. from Koin) to the
 * subtree as [LocalLogoImageLoader].
 */
@Composable
fun ProvideLogoImageLoader(loader: LogoImageLoader?, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalLogoImageLoader provides loader) {
        content()
    }
}

private sealed interface LogoUiState {
    data object Loading : LogoUiState
    data class Loaded(val bitmap: ImageBitmap) : LogoUiState
    data object Empty : LogoUiState
}

/**
 * Channel logo with async loading, memory+disk caching and a graceful
 * placeholder. Never crashes on a bad URL — a failed load just shows the
 * placeholder.
 *
 * The "loading" state deliberately reuses the same static placeholder as
 * "empty": an animated CircularProgressIndicator per visible channel row keeps
 * the renderer busy redrawing every frame while scrolling a long channel list
 * (the very jank this loader is meant to fix). The first frame of the initial
 * is shown immediately and swapped for the bitmap when it arrives.
 *
 * @param placeholderText first character (channel initial) shown as placeholder
 * @param showPlaceholder when `false` no grey placeholder square is drawn for a
 *   missing/failed image (used for TMDB posters: a missing poster renders as
 *   empty space instead of a grey box)
 */
@Composable
fun ChannelLogo(
    url: String?,
    modifier: Modifier = Modifier,
    placeholderText: String? = null,
    cornerRadius: Dp = TVNextDimens.ItemSpacingSmall,
    showPlaceholder: Boolean = true,
) {
    val loader = LocalLogoImageLoader.current
    var state by remember(url, loader) { mutableStateOf<LogoUiState>(LogoUiState.Loading) }

    LaunchedEffect(url, loader) {
        if (url.isNullOrBlank() || loader == null) {
            state = LogoUiState.Empty
            return@LaunchedEffect
        }
        state = LogoUiState.Loading
        val bitmap = loader.load(url)
        state = if (bitmap != null) LogoUiState.Loaded(bitmap) else LogoUiState.Empty
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(
                if (showPlaceholder) {
                    MaterialTheme.colorScheme.surfaceVariant
                } else {
                    androidx.compose.ui.graphics.Color.Transparent
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        when (val current = state) {
            is LogoUiState.Loaded -> Image(
                bitmap = current.bitmap,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
            // Loading and Empty share one static placeholder (see the class doc):
            // no spinner, no per-frame animation while scrolling.
            is LogoUiState.Loading,
            is LogoUiState.Empty,
            -> if (showPlaceholder) {
                placeholderText
                    ?.firstOrNull()
                    ?.let { initial ->
                        Text(
                            text = initial.toString(),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
            }
        }
    }
}
