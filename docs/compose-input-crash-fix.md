# 输入框崩溃修复（2026-10-14）

## 日志

用户日志在 14:13:44 和 14:14:04 两次记录主线程致命异常：

```text
java.lang.AbstractMethodError:
androidx.compose.foundation.style.CustomStyle.applyStyle(CustomStyleScope)
receiver: androidx.compose.material3.OutlinedTextFieldDefaults$$ExternalSyntheticLambda0
```

异常发生在输入框样式解析与页面组合阶段，尚未进入备份文件写入或网络上传逻辑。日志没有提供足够信息判定具体是哪一个输入弹窗，但同类输入框都有可能受影响。

## 原因与修改

原实际运行依赖为 Material3 `1.5.0-alpha17` 与 Compose Foundation `1.12.0`。版本目录的旧 BOM 为 `2026.03.01`，主题库 MaterialKolor `5.0.1` 的传递依赖提升了基础库版本。输入框使用的实验性样式接口发生不兼容，导致编译能通过，但显示时找不到接口实现。

- Compose BOM 更新为 `2026.09.00`，统一基础库到 `1.12.1` 系列。
- Material3 更新为 `1.5.0-alpha27`，使用匹配新样式接口的组件实现。
- 新组件最低支持 Android 7.0（API 24），同步提高最低系统要求，未强制绕过组件的系统版本要求；日志中的手机系统满足此要求。
- 播放器模块补上同一个 BOM，防止其编译依赖与主应用运行依赖分别解析成不同版本。
- 主题分段按钮适配新版的 `ToggleButtonDefaults.colors` 接口，保留既有颜色和布局。
- 共存版版本名更新为 `0.16.4-ui-preview5-avbox`。

按用户要求仅做依赖解析核对和编译检查，没有运行自动化测试或设备交互测试。设备上的输入框显示、编辑与备份操作仍需用户验证。
