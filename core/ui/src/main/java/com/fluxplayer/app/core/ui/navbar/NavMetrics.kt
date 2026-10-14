package com.fluxplayer.app.core.ui.navbar

/** 槽位映射保持与 AVBox 底栏一致，Flux 当前使用横向布局。 */
object NavMetrics {
    fun actionSlotFor(tabCount: Int): Int = tabCount / 2
    fun slotIndexOfTab(tab: Int, actionSlot: Int?): Int = if (actionSlot != null && tab >= actionSlot) tab + 1 else tab
    fun tabIndexOfSlot(slot: Int, actionSlot: Int?): Int = if (actionSlot != null && slot > actionSlot) slot - 1 else slot
}

data class NavGlassConfig(val navbarEnabled: Boolean, val blurDp: Float, val distortionDp: Float, val translucency: Float, val dispersion: Boolean) {
    val containerAlphaScale: Float get() = 2f - translucency * 2f
    val contentBrightness: Float get() = (0.5f - translucency) * 0.24f
    val contentContrast: Float get() = 1f + (translucency - 0.5f) * 0.5f
}
