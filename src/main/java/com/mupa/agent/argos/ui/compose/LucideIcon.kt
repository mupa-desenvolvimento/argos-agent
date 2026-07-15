package com.mupa.agent.argos.ui.compose

import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.ImageRequest

@Composable
fun LucideIcon(
    name: String,
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified,
    contentDescription: String? = null,
) {
    val context = LocalContext.current
    val url = "https://api.iconify.design/lucide:${name.trim()}.svg"
    val safeModifier = modifier.aspectRatio(1f)
    AsyncImage(
        model = ImageRequest.Builder(context)
            .data(url)
            .crossfade(true)
            .build(),
        contentDescription = contentDescription,
        modifier = safeModifier,
        contentScale = ContentScale.Fit,
        colorFilter = if (tint == Color.Unspecified) null else ColorFilter.tint(tint),
    )
}
