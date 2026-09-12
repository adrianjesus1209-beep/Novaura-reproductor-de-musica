package com.novaura.music.ui.components

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

@Composable
fun CustomBackgroundBox(
    bgImageUri: String?,
    darkness: Float,
    blurRadius: Float,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(modifier = modifier.fillMaxSize()) {
        // Base app background color
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        )

        // Custom Background Image if set
        if (!bgImageUri.isNull_or_Empty()) {
            val blurDp = blurRadius.coerceIn(0f, 30f).dp
            val darknessAlpha = darkness.coerceIn(0f, 0.95f)

            AsyncImage(
                model = Uri.parse(bgImageUri),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (blurDp > 0.dp) Modifier.blur(blurDp) else Modifier)
            )

            // Dark Overlay
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = darknessAlpha))
            )
        }

        // Screen Content
        content()
    }
}

private fun String?.isNull_or_Empty(): Boolean = this.isNullOrEmpty()
