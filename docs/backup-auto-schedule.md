# 自动备份间隔与触发时机（2026-10-14）

自动备份原来固定在“应用退到后台时”执行，并按 5 分钟硬编码限频。现在间隔和时机都进了备份设置，本地目录与 WebDAV 两种模式共用同一套计划。

## 新增设置

备份与恢复 → 自动备份与检查：

- 备份间隔：5 分钟、15 分钟、30 分钟、1 小时、3 小时、5 小时、12 小时、1 天、2 天、1 周，默认 5 分钟（与旧版硬编码节流一致，升级后频率不变）。
- 备份时机：应用退到后台时（默认）、打开应用后备份一次、打开应用后与退到后台时。
- 两项都只在自动备份已开启时可点；打开“自动检查新备份”的行为不变。

## 实现要点

- `BackupWebDavConfig` 新增 `autoBackupIntervalMinutes`、`autoBackupTrigger`、`lastAutoBackupAt`。上次备份时刻改存 DataStore，进程重启后间隔仍然有效，原先的内存变量 `lastAutoBackupAtMs` 已删除。
- 间隔字段只接受预设区间内的值：`effectiveAutoBackupIntervalMinutes` 把旧配置或手工写坏的值收敛到 5 分钟到 1 周。时机字段遇到未知值时按“退到后台”处理，因此旧配置升级后行为不变。
- “打开应用后备份一次”挂在 `NextPlayerApplication.onCreate`，退到后台挂在 `MainActivity.onStop`，两处都调用 `AutoBackupHelper.performAutoBackup(onLaunch = …)`，由触发时机决定是否执行。
- 节流仍是“距上次不少于间隔”，不是定时器。选“打开应用后备份一次”时，只有进程重新启动才会再备份；长时间不关闭应用不会按间隔补备份。
- 备份前先把 `lastAutoBackupAt` 写回设置，失败的备份同样占用间隔，避免服务异常时每次切后台都重试。
- 恢复备份设置时仍关闭自动备份，并保留本机 `lastAutoBackupAt`，不被来源设备的时间戳挡住首次备份。
- 旧的 `force` 参数没有被任何调用方使用，随本次改造删除。

## 验证

`:core:model` 没有单元测试源集，逻辑测试放在 `:core:data:testDebugUnitTest`。新增三项测试覆盖间隔兜底、时机判断和旧配置反序列化默认值，均通过；`ktlintCheck` 与 `:app:assembleDebug` 通过。同目录下原有的 `legacyJsonWithBomCanBeImportedWithoutZipFallback` 仍然失败，已在未修改的 `main` 上复现，与本次改动无关。
