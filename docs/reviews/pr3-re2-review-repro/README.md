# PR #3 RE2 修复复审工具

固定 head：a6587d63403160060aaa49d86b56ad6ac1ee22c6。

```sh
python3 run_wiring_probes.py /path/to/DSU-Sideloader-RE --deps /tmp/dsu-review-deps
```

要求 Python 3、带编译器模块的 Java 17+、首次下载 Maven Central 固定 Kotlin/JUnit 依赖的网络；无需 Android SDK。

在此 head 期望：150 项项目测试、24 项仓库接线 probe、1 项新增 SystemOutcomeReviewTest 全部通过，退出码 0。

工具复制自当前 head 的 tools/wiring-probes，仅增加：

- CmdRunner 边界的 linesHook，默认无日志；只在新增测试中注入一条系统取消文本。
- SystemOutcomeReviewTest：完成真实提取方法的普通安装交接后，注入 NOT_STARTED + INSTALL_CANCELLED，交由真实 LogcatDiagnostic 解析及实际错误回调处理。断言 ERROR_CANCELED、mount=1、pending=null、无逃逸异常、下一任务可开始。
- runner 编译并执行新增用例，退出码纳入总结果。原仓库断言保持不变。

这项对照与原 RE2 的“我方日志初始化抛异常”组成一组：前者允许已确认的恢复，后者保持 SYSTEM_UNKNOWN 且只允许显式恢复。不是靠一律禁止挂载使测试通过。

original-re2.log 是上一轮审查工具原样执行的结果（150 + 23 通过）；system-outcome.log 是本目录扩展工具的结果（150 + 25 通过）。HomeWiringHarness.kt 为生成快照，运行时重新提取；源码 checkout 不变。

沿用原工具的边界替身：Session、Preparation 文件处理、scope、IPC/Intent、DataStore、权限/Settings、CmdRunner 日志传输等。真实 LogcatDiagnostic、任务协议、ViewModel 提取方法及存储恢复策略参与运行，但不是完整 Android ViewModel/Compose/生命周期/Binder/设备测试。注入日志不能冒充真实设备事件。
