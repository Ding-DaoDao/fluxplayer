package com.fluxplayer.app.feature.videopicker.composables

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import coil3.compose.AsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.fluxplayer.app.core.common.CloudPlayHeaders
import com.fluxplayer.app.core.model.WebDavResource
import com.fluxplayer.app.core.ui.R as UiR

@Composable
fun FileTypeIcon(item: WebDavResource, modifier: Modifier = Modifier) {
    val thumbnailUrl = item.thumbnailUrl
    if (thumbnailUrl.isNullOrBlank()) {
        StaticIcon(item, modifier)
    } else {
        val context = LocalContext.current
        val iconRes = getIconRes(item)
        val headers = remember(thumbnailUrl) {
            if (CloudPlayHeaders.isSelfAuthenticatingUrl(thumbnailUrl)) emptyMap()
            else {
                val host = Uri.parse(thumbnailUrl).host ?: ""
                CloudPlayHeaders.getHeaders(host)
            }
        }
        val networkHeaders = remember(headers) {
            if (headers.isEmpty()) null
            else NetworkHeaders.Builder().apply {
                headers.forEach { (k, v) -> set(k, v) }
            }.build()
        }
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(thumbnailUrl)
                .apply { networkHeaders?.let { httpHeaders(it) } }
                .crossfade(true)
                .build(),
            contentDescription = null,
            modifier = modifier,
            contentScale = ContentScale.Crop,
            // TODO(P2): 三态目前仍指向同一张图，需拆成 骨架占位 / 失败提示 / 类型图标。
            // 改造需要新增 drawable 资源（仓库内暂无合适的占位图），本次先靠
            // ImageLoaderModule 的 CoverImageLoader 日志区分失败原因，不阻塞本次修复。
            placeholder = painterResource(iconRes),
            error = painterResource(iconRes),
            fallback = painterResource(iconRes),
        )
    }
}

@Composable
private fun StaticIcon(item: WebDavResource, modifier: Modifier) {
    Image(
        painter = painterResource(getIconRes(item)),
        contentDescription = null,
        modifier = modifier,
        contentScale = ContentScale.Fit,
    )
}

private fun getIconRes(item: WebDavResource): Int = when (item.fileType) {
    WebDavResource.FileType.FOLDER -> UiR.drawable.ic_file_folder
    WebDavResource.FileType.VIDEO -> UiR.drawable.ic_file_video
    WebDavResource.FileType.AUDIO -> UiR.drawable.ic_file_audio
    WebDavResource.FileType.IMAGE -> UiR.drawable.ic_file_image
    WebDavResource.FileType.DOC -> UiR.drawable.ic_file_doc
    WebDavResource.FileType.ARCHIVE -> UiR.drawable.ic_file_archive
    WebDavResource.FileType.UNKNOWN -> UiR.drawable.ic_file_unknown
}
