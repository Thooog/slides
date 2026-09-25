# SLIDES 项目长期笔记

## 流程约定
- Task 工作流：`.ai-workspace/tasks/Txxx.md` → 实现/修复 → `.ai-workspace/reviews/Rxxx.md` 追加 Executor Revision → 独立 Reviewer 判定。Executor 不得自行宣布 APPROVED。
- 权限变更验证：用户拒绝 pm 命令方式，权限切换一律由用户在系统设置手动执行，Executor 列 manual_test_checklist 清单交用户打勾。
- Project_state.md 只在 Review 通过后由对应流程更新，Executor 不主动改。

## 技术要点
- 冻结栈：Kotlin / Compose / Room / ORT。当前仅 Compose 阶段（M1 只读相册），Room/ORT 未集成。
- 媒体权限（Android 14+）：MIUI 完整授权时会同时授予 READ_MEDIA_VISUAL_USER_SELECTED；currentScope 判定必须 IMAGES/VIDEO 优先、user-selected 兜底（官方推荐顺序），否则完整授权误判 SELECTED。
- 设备：Xiaomi 13（2211133C / fuxi / Android 15 / API 35 / arm64），serial 67239b8e；媒体规模约 2.7 万项。
- 构建环境：JDK17；本机 Bash PATH 损坏（grep/head/tr 不可用），用 PowerShell 执行命令并将输出 Out-File 落盘后 Read。
- 截图证据可能含用户真实私人媒体，按 Reviewer 要求不扩散，后续验证建议用非敏感素材。
