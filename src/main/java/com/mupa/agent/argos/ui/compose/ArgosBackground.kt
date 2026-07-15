package com.mupa.agent.argos.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush

@Composable
fun ArgosBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val bg = Brush.linearGradient(
        colors = listOf(
            ArgosTokens.Dark,
            ArgosTokens.Navy,
            ArgosTokens.Dark,
        ),
    )
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(bg),
        content = content,
    )
}

