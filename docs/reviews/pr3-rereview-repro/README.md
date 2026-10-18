# PR #3 二轮复审工具：恢复状态与 RE2

审查 head：06a58212add84d605bef87091542c515bf00e093。

```sh
python3 run_wiring_probes.py /path/to/DSU-Sideloader-RE --deps /tmp/dsu-review-deps
```

要求 Python 3、带编译器模块的 Java 17+、首次下载 Maven Central 固定依赖的网络；无需 Android SDK。

此 head 的预期：150 项项目测试通过，仓库原 17 项 probe 和新增 RecoveryReviewTest 5 项通过，LoggingHandoffReviewTest 1 项失败，故总退出码 1。不是编译错误。

改编自此 head 的 tools/wiring-probes。保留原断言，增加：

- 提取实际的 refreshStorageRestorePending/showStorageRestore/resetInstallationCard/onRestoreStorage/querySystemDsuState/卡片 ACK 与 action 方法，不再把存储状态刷新作为空替身。
- 提取实际 startLogging，编译真实 LogcatDiagnostic。CmdRunner 仅替换外部日志传输（run/readLine 默认无输出，destroy 可注入异常）；诊断初始化与故障路由来自生产源码。
- Session 增加可控 root 边界，IPrivilegedService 增加 installationProgress 边界。实际 root 清理流程与状态检查方法参与执行，但不是真实设备 root/gsid。
- RecoveryReviewTest：实际 pending 状态刷新、普通 ACK 后提示、显式恢复、startActivity 抛异常、root 清理成功/失败、安装中阻止恢复，共五项。
- LoggingHandoffReviewTest：先以真实请求/取消/返回流程留下 logger；普通 onConfirm 入口启动新安装；CmdRunner.destroy 的故障边界先等新提交 Job 完成，确认 handedToSystem=true，再抛 IOException。主入口漏传 requestFailed=true，实际观察 ERROR、mount=1、pending=null，故断言失败。
- prefHook 只控制真实入口读取的偏好；日志上下文生成与非本场景日志回调仍使用替身。没有修改生产故障处理或直接模拟 onTaskFailed 结果。

HomeWiringHarness.kt 是生成快照供核对，每次运行会重新从 checkout 提取 30 个 HomeViewModel 方法与 StorageManager 写入方法。checkout 受控文件不变，classes 位于临时目录。

这不是完整 Android ViewModel、Compose、生命周期或 Binder 集成测试。按钮对应的状态与动作已在 JVM 验证，实际渲染/触摸和外部系统真实行为没有设备证据。

同目录日志：original-review.log（上一轮工具原样运行：150 + 14 通过）；project-wiring.log（当前仓库工具：150 + 17 通过）；logging-handoff.log（扩展工具：150 + 22 通过，新增日志交接 1 项失败）。runner 底部继承的 Expected 文案描述原列表，RE2 的结果以 LoggingHandoffReviewTest 断言为准。
