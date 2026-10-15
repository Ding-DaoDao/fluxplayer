# 自动备份间隔与触发时机（2026-10-14）

自动备份原来固定在“应用退到后台时”执行，并按 5 分钟硬编码限频。现在间隔和时机都进了备份设置，本地目录与 WebDAV 两种模式共用同一套计划。

## 新增设置

备份与恢复 → 自动备份与检查：

- 备份间隔：15 分钟、30 分钟、1 小时、3 小时、5 小时、12 小时、1 天、2 天、3 天、5 天、1 周，默认 1 周。
- 备份时机：应用退到后台时（默认）、打开应用后备份一次、打开应用后与退到后台时。
- 保留份数：3 / 5 / 10 / 20 / 30 / 不限制，默认 10。本地与 WebDAV 各自按这个份数保留最近 N 份，超出部分在备份成功后清理。
- 三项都只在自动备份已开启时可点；打开“自动检查新备份”的行为不变。
- 本地目录与 WebDAV 不再是二选一：两个开关各自独立，可同时开启，一次触发会先写本地再上传 WebDAV。模式仍由单个 `autoBackupSyncMode` 字段表达（`off` / `local` / `remote` / `both`），旧配置无需迁移。

## 实现要点

- `BackupWebDavConfig` 新增 `autoBackupIntervalMinutes`、`autoBackupTrigger`、`lastAutoBackupAt`。上次备份时刻改存 DataStore，进程重启后间隔仍然有效，原先的内存变量 `lastAutoBackupAtMs` 已删除。
- 间隔字段只接受预设区间内的值：`effectiveAutoBackupIntervalMinutes` 把旧配置或手工写坏的值收敛到 15 分钟到 1 周。时机字段遇到未知值时按“退到后台”处理，因此旧配置升级后行为不变。
- 默认间隔为一周。此前的 5 分钟档已从预设中移除，若历史配置里存有 5，会被收敛到当前最小的 15 分钟。
- “打开应用后备份一次”挂在 `NextPlayerApplication.onCreate`，退到后台挂在 `MainActivity.onStop`，两处都调用 `AutoBackupHelper.performAutoBackup(onLaunch = …)`，由触发时机决定是否执行。
- 节流仍是“距上次不少于间隔”，不是定时器。选“打开应用后备份一次”时，只有进程重新启动才会再备份；长时间不关闭应用不会按间隔补备份。
- 备份前先把 `lastAutoBackupAt` 写回设置，失败的备份同样占用间隔，避免服务异常时每次切后台都重试。
- 两个目标各自校验可用性（本地需已授权目录，WebDAV 需地址合法）：只跑已配置好的那个，未配置的不阻塞另一个。`lastAutoBackupAt` 在任一目标执行前统一写回，两个目标共用同一节流，任一方失败也一起等到下一个间隔。
- 恢复备份设置时仍关闭自动备份，并保留本机 `lastAutoBackupAt`，不被来源设备的时间戳挡住首次备份。
- 旧的 `force` 参数没有被任何调用方使用，随本次改造删除。

## 保留策略（2026-10-15）

自动备份此前只增不减，目录会无限堆积。现在每个目标只保留最近 N 份，备份成功后顺带清理。

### 规则

- 保留份数由 `autoBackupRetentionCount` 控制，取值 3 / 5 / 10 / 20 / 30 / 0，默认 10；`0` 表示不清理。
- 本地与 WebDAV **各自独立计算**（各自的目录里各留 N 份），但共用同一个数字。
- 只在**自动备份成功后**清理，且“谁成功清谁”。手动「导出到本地目录」不触发清理——手动产物属于用户主动行为，一律保留。
- 排序优先用**文件名里的时间戳**（`backupFileTimestampKey`），比文件修改时间可靠；解析不出时间戳的文件不参与清理。

### 安全底线

删除是不可逆操作，因此清理路径比备份路径保守得多：

1. 只删文件名**严格匹配**备份格式的文件（`isPrunableBackupFileName`），正则要求「前缀 + 可选设备名 + 完整时间戳 + 可选毫秒与随机串 + .zip」，同时覆盖旧版（无毫秒与随机串）与当前版本两种命名。**列目录仍用宽松的 `isBackupFileName`**，两者刻意分开：宽松匹配用于读取，严格匹配用于删除，宁可漏清也不误删。
2. `keep <= 0` 时直接跳过，不做任何删除。
3. 本次刚创建的文件（`except` 参数）**永不删除**，即使时间戳排序异常也不会误伤自己。
4. 单个文件删除失败只记日志，不影响其余文件，也不影响备份结果。

### 实现

- `BackupManager.pruneLocalBackups(directory, keep, except)`：用 `DocumentsContract.buildChildDocumentsUriUsingTree` + `query` 列举本地目录，`DocumentsContract.deleteDocument` 删除。
- `BackupManager.pruneRemoteBackups(config, keep, except)`：复用 `webDavRepository.listDirectory` 与 `delete`。
- `AutoBackupHelper.performAutoBackup` 在各自备份成功后调用对应清理，清理结果只记日志。

## 验证

`:core:model` 没有单元测试源集，逻辑测试放在 `:core:data:testDebugUnitTest`。新增三项测试覆盖间隔兜底、时机判断和旧配置反序列化默认值，均通过；`ktlintCheck` 与 `:app:assembleDebug` 通过。同目录下原有的 `legacyJsonWithBomCanBeImportedWithoutZipFallback` 仍然失败，已在未修改的 `main` 上复现，与本次改动无关。
