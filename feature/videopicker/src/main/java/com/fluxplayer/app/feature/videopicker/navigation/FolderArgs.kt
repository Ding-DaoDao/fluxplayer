package com.fluxplayer.app.feature.videopicker.navigation

import android.net.Uri
import androidx.lifecycle.SavedStateHandle

internal class FolderArgs private constructor(
    val folderId: String?,
) {
    constructor(savedStateHandle: SavedStateHandle) : this(
        folderId = savedStateHandle.get<String>(folderIdArg)?.let { Uri.decode(it) },
    )
}
