# PR #3 独立审查 RE1 复现

固定 head：008aec62c343c39e5d350d052e270fd3a7b34dfb；基线：9796d2725e6eb9744f95cc685d96c3ce3110d873。

```sh
python3 run_wiring_probes.py /path/to/DSU-Sideloader-RE --deps /tmp/dsu-review-deps
```

要求 Python 3、带编译器模块的 Java 17+，首次下载 Maven Central 的固定 Kotlin/JUnit 依赖；无需 Android SDK。

工具基于该 head 的 tools/wiring-probes，只修改提取/边界前缀与 runner，原项目测试和原 12 个 probe 的断言保留。新增 RetryHistoryReviewTest 两项；期望在此 head 上失败（退出码 1），而 149 个项目测试 + 12 个原 probe 通过。

变更：

- 额外原样提取 remountSdCardsIfOwnedBy，删除其旧空替身；存储恢复提示刷新仍为空替身。
- harness 提供 retryJob/submissionJob/pendingRecord 读取，便于在真实任务请求结束后继续操作。
- 内存存储增加 persistHook，注入一次真实调用链可收到的 IOException；没有改动 SdCardRemounter 或取消/错误逻辑。
- 授予 READ_LOGS 的替身返回值使初次实际卡片保持 WAITING_USER_CONFIRMATION（有重试按钮）；日志读取本身仍用替身。
- 取消场景在第二次 unmount 的 IPC 边界阻塞，使第二个 step 已登记但未 forceStop/startActivity；随后取消并放行。
- 失败场景在第二次持久化待恢复记录时失败，此时较早系统交接仍未确认结束。
- 两个场景都断言不得自动 mount。此 head 均观察到 mount=1、pending 最终被清空，且无协程逃逸异常，故失败。
- mount 调用早于异步保存清空记录，日志前会短暂等待该保存完成，避免把中间态当最终记录。

生成的 HomeWiringHarness.kt 附上供核对；每次运行会重提取。源码 checkout 不会被修改，classes 位于临时目录。

真实 gate/TaskSteps/RetryEntry/DSU handler/Submission/SdCardRemounter/挂载恢复辅助方法参与运行，Android 外层 scope、Session、IPC、Intent、权限/Settings、持久化等边界使用替身。不是完整 Android ViewModel/生命周期/Binder 集成测试。

仓库原工具的独立日志为上一层 project-wiring.log（退出码 0）；带新增用例的独立日志为上一层 history-probe.log（退出码 1）。runner 底部继承的 Expected 文案只描述原测试清单；新增两项的结果见紧邻的 RetryHistoryReviewTest 失败输出。
