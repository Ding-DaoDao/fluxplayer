package dev.anilbeesetti.nextplayer.feature.videopicker.composables

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import dev.anilbeesetti.nextplayer.core.model.WebDavResource
import dev.anilbeesetti.nextplayer.core.ui.R as UiR

@Composable
fun FileTypeIcon(item: WebDavResource, modifier: Modifier = Modifier) {
    val resId = when (item.fileType) {
        WebDavResource.FileType.FOLDER -> UiR.drawable.ic_file_folder
        WebDavResource.FileType.VIDEO -> UiR.drawable.ic_file_video
        WebDavResource.FileType.AUDIO -> UiR.drawable.ic_file_audio
        WebDavResource.FileType.IMAGE -> UiR.drawable.ic_file_image
        WebDavResource.FileType.DOC -> UiR.drawable.ic_file_doc
        WebDavResource.FileType.ARCHIVE -> UiR.drawable.ic_file_archive
        WebDavResource.FileType.UNKNOWN -> UiR.drawable.ic_file_unknown
    }
    Image(
        painter = painterResource(resId),
        contentDescription = null,
        modifier = modifier,
        contentScale = ContentScale.Fit,
    )
}
