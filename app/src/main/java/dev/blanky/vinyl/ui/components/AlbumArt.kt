package dev.blanky.vinyl.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import coil.compose.AsyncImage
import dev.blanky.vinyl.R

/**
 * Okładka z Coil + eleganckim placeholderem, gdy URL brak lub się nie wczyta.
 */
@Composable
fun AlbumArt(
    url: String?,
    modifier: Modifier = Modifier,
    cornerRadiusDp: Int = 10,
) {
    Box(modifier) {
        Box(
            Modifier
                .matchParentSize()
                .clip(RoundedCornerShape(cornerRadiusDp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        val model = url?.takeIf { it.isNotBlank() }
        if (model != null) {
            AsyncImage(
                model = model,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .matchParentSize()
                    .clip(RoundedCornerShape(cornerRadiusDp)),
                placeholder = painterResource(R.drawable.ic_cover_placeholder),
                error = painterResource(R.drawable.ic_cover_placeholder),
            )
        } else {
            Box(
                Modifier
                    .matchParentSize()
                    .clip(RoundedCornerShape(cornerRadiusDp)),
                contentAlignment = androidx.compose.ui.Alignment.Center,
            ) {
                androidx.compose.material3.Icon(
                    imageVector = androidx.compose.material.icons.Icons.Filled.MusicNote,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
            }
        }
    }
}
