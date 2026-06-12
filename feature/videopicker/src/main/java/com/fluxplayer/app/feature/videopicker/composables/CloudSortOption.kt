package com.fluxplayer.app.feature.videopicker.composables

data class CloudSortOption(
    val label: String,
    val isSelected: Boolean,
    val onClick: () -> Unit,
)
