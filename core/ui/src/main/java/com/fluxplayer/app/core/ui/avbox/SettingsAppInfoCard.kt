package com.fluxplayer.app.core.ui.avbox

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.fluxplayer.app.core.ui.R
import com.fluxplayer.app.core.ui.designsystem.NextIcons

private val HeaderCardShape = RoundedCornerShape(32.dp)

private val VersionPillShape = RoundedCornerShape(24.dp)

private val AppBadgeSize = 76.dp

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AppInfoHeaderCard(versionName: String) {
    val scheme = MaterialTheme.colorScheme
    val contentColor = scheme.onPrimaryContainer
    val context = LocalContext.current
    val iconSizePx = with(LocalDensity.current) { AppBadgeSize.roundToPx() }
    val appIcon = remember(context, iconSizePx) {
        context.packageManager.getApplicationIcon(context.applicationInfo)
            .toBitmap(width = iconSizePx, height = iconSizePx)
            .asImageBitmap()
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(HeaderCardShape)
            .background(scheme.primaryContainer)
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(AppBadgeSize)
                    .clip(MaterialShapes.Cookie12Sided.toShape())
                    .background(scheme.surfaceBright),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    bitmap = appIcon,
                    contentDescription = stringResource(R.string.app_logo_desc),
                    modifier = Modifier.size(AppBadgeSize),
                )
            }
            Spacer(Modifier.width(20.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Flux Player",
                    style = MaterialTheme.typography.headlineMedium,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = contentColor,
                )
                Text(
                    text = stringResource(R.string.settings_media_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = contentColor.copy(alpha = 0.75f),
                )
            }
        }
        VersionPill(versionText = versionName.ifEmpty { "-" })
    }
}

@Composable
private fun VersionPill(versionText: String) {
    val scheme = MaterialTheme.colorScheme
    val contentColor = scheme.onPrimaryContainer
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(VersionPillShape)
            .background(scheme.surfaceContainerLow)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = NextIcons.Decoder,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(28.dp),
        )
        Spacer(Modifier.width(16.dp))
        Column {
            Text(
                text = "当前版本",
                style = MaterialTheme.typography.bodyMedium,
                color = contentColor.copy(alpha = 0.75f),
            )
            Text(
                text = versionText,
                style = MaterialTheme.typography.titleLarge,
                fontSize = 16.sp,
                color = contentColor,
            )
        }
    }
}
