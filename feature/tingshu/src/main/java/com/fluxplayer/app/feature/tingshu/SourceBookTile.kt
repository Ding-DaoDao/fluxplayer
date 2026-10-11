package com.fluxplayer.app.feature.tingshu

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.fluxplayer.app.core.ui.cache.rememberBookCoverImageLoader
import com.fluxplayer.app.core.ui.designsystem.NextIcons
import com.fluxplayer.app.core.ui.theme.FluxTheme
import com.github.eprendre.tingshu.utils.Book

@Composable
internal fun SourceBookTile(book: Book, cover: Any?, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = FluxTheme.colorScheme.surfaceContainer),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.fillMaxWidth().aspectRatio(0.75f), contentAlignment = Alignment.Center) {
                Icon(NextIcons.Headset, contentDescription = null, tint = FluxTheme.colorScheme.onSurfaceVariant)
                AsyncImage(
                    model = cover,
                    imageLoader = rememberBookCoverImageLoader(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Text(
                book.title,
                modifier = Modifier.padding(horizontal = 8.dp),
                style = FluxTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = FluxTheme.colorScheme.onSurface,
                maxLines = 2,
                minLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                book.author.ifBlank { "点击收听" },
                modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
                style = FluxTheme.typography.labelSmall,
                color = FluxTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
