# PR #2 第五轮独立验证材料

审查 head：`9796d2725e6eb9744f95cc685d96c3ce3110d873`。

```sh
python3 run_fifth_review.py /path/to/DSU-Sideloader-RE --deps /tmp/dsu-review-deps
```

需要 Python 3、带编译器模块的 Java 17+ 和首次下载 Maven Central 依赖的网络。使用固定 Kotlin 1.8.20、coroutines 1.6.4、JUnit 4.13.2 等版本；无需 Android SDK。

运行四组：全部 136 项项目测试；原 TR1/TR2 两项；原 FR1 一项；新增实际终态接线四项。此 head 全部通过，退出码 0。项目的 exceptionWhileReportingStillReleasesGate 会将主动注入的异常输出到后台线程日志，即使 JUnit 显示通过；这不是 Android 应用不会崩溃的证明，见报告。

original-fourth-results.log 来自原版 run_fourth_review.py：126 项 + 原三项 probe，通过。旧 runner、extract_wiring.py 和 FourthReviewWiringTest.kt 与交接分支 c26982e 中的已发布文件逐字一致。旧 runner 的固定 JUnit 清单没有新增的 FourthReviewRegressionTest（10 项）。

本目录扩展工具只改变测试清单、增加四项 probe 与读取卡片错误详情的 harness 辅助方法，额外提取 currentOrNewTask / onClickRetryInstallation 供源码核对；没有改动原三项 probe 的断言。HomeWiringHarness.kt 是新 head 的生成结果，运行时会重新提取。

四项新增 probe 验证取消后写入最终成功、写入与清理同时失败、重复准备终态回调、无取消写入失败。第一个场景的暂停点位于 getExternalFilesDir：取消先发生，随后真实脚本写入完成，再处理生成成功回调。

提取真实 HomeViewModel 及 StorageManager 写入方法，生产 gate/步骤/DSU 与 ADB handler/脚本生成器直接参与编译。Session、Preparation 文件 I/O、scope、Android 存储上下文/偏好/权限/Settings/IPC/Intent 等边界用替身。不是完整 Android ViewModel/生命周期/Binder 集成测试，不验证真实 gsid、SAF 提供方、挂载或设备上的重试 UI。

应用 checkout 不会被修改；classes 使用临时目录；生成的 harness 写入工具目录。results.log 保留完整编译警告与测试输出。
