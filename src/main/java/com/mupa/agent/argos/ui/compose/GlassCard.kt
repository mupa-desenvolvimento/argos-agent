package com.mupa.agent.argos.ui.compose

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 16.dp,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    elevation: Dp = 10.dp,
    containerAlpha: Float = 0.62f,
    borderAlpha: Float = 0.10f,
    content: @Composable () -> Unit,
) {
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(cornerRadius)
    Card(
        modifier = modifier
            .clip(shape),
        shape = shape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = containerAlpha),
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = borderAlpha)),
        elevation = CardDefaults.cardElevation(defaultElevation = elevation),
    ) {
        androidx.compose.foundation.layout.Column(modifier = Modifier.padding(contentPadding)) {
            content()
        }
    }
}
