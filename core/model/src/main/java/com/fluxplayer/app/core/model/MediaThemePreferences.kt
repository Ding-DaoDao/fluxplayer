package com.fluxplayer.app.core.model

/** 配色来源：壁纸动态配色或自定义种子色。 */
enum class ThemeColorSource { SYSTEM, CUSTOM }

/** 与 Material Kolor 配色算法对应，名称保持稳定以兼容偏好备份。 */
enum class ThemePaletteStyle(val algorithm: String) {
    TONAL_SPOT("TonalSpot"),
    VIBRANT("Vibrant"),
    EXPRESSIVE("Expressive"),
    NEUTRAL("Neutral"),
    MONOCHROME("Monochrome"),
    FIDELITY("Fidelity"),
    CONTENT("Content"),
    RAINBOW("Rainbow"),
    FRUIT_SALAD("FruitSalad"),
}
