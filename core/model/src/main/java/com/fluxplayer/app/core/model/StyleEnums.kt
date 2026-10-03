package com.fluxplayer.app.core.model

/**
 * 底部导航样式。
 *
 * [DOCK] 胶囊悬浮舱：整条是一个大胶囊，选中项展开显示文字，未选中只有图标。省横向空间。
 * [FULL_BAR] 通栏导航条：贴底通宽，图标在上标签在下，选中态是固定 64×32 指示胶囊。
 *
 * 与 core/ui 的 `NavStyle` 一一对应，此处放 model 层是为了让偏好可被 DataStore 序列化。
 */
enum class NavStyle(val label: String) {
    DOCK("悬浮舱"),
    FULL_BAR("通栏"),
    ;

    companion object {
        val Default = DOCK
    }
}

/**
 * 界面质感。
 *
 * [GLASS] 玻璃：半透明 + 高光边 + 模糊，用于真正浮在内容之上的东西（导航舱、弹层、悬浮卡）。
 * [FLAT] 扁平：不透明 tonal 面 + 发丝线，页面内的长列表用它 —— 半透明+高光+投影堆在一起，
 * 一屏出现五六次就糊成一片，层级反而消失。
 */
enum class SurfaceStyle(val label: String) {
    GLASS("玻璃"),
    FLAT("扁平"),
    ;

    companion object {
        val Default = GLASS
    }
}
