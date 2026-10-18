# PR #2 第四轮复现工具

审查版本：`cc6aec389d3c187249243783bb70eab9fe261176`。

```sh
python3 run_fourth_review.py /path/to/DSU-Sideloader-RE --deps /tmp/dsu-review-deps
```

需要 Python 3、带编译器模块的 Java 17+、Maven Central 网络（首次下载 Kotlin/JUnit 等固定版本依赖），无需 Android SDK。源码 checkout 不会被修改；提取结果写到工具目录，classes 放临时目录。脚本当前使用 Java 编译器的 `-source 17 -target 17`，在审查环境的 OpenJDK 21 上运行。

期望结果：125 个项目测试通过，TR1/TR2 两项 probe 通过，FR1 的 HandoffExceptionTest 失败；故脚本退出码为 1。这是发现应用协调缺陷的断言失败，不是编译失败。

extract_wiring.py 从 checkout 原样提取 16 个 HomeViewModel 方法，以及 StorageManager.writeStringToExternalFileDir。HomeWiringHarness.kt 是该版本的生成结果供核对，运行时会重新生成。生产 TaskSteps、gate、提交协议、DSU/ADB handler 和脚本生成器直接参与编译。

替身：外层 scope、Session/偏好、Preparation 文件处理、存储上下文、权限/Settings、Intent、IPC、DataStore 等 Android 边界；无关的日志启动/挂载辅助方法也用替身。本工具不是完整 Android ViewModel/生命周期/Binder 集成测试。

TR1/TR2 的适配：共享 installationJob 已去掉；新的 taskKey 接口模拟按任务清理。TR1 保留“清理阻塞期间拒绝新任务”及文件存活断言，实际目录隔离另由项目 TaskWorkspaces 测试覆盖。TR2 将旧的“等待取消先解锁”改为“窗口内 B 被拒、A 退出后 B 才开始”，禁止旧 startActivity，并增加禁止 forceStop。

FR1 跑实际 onConfirm → 准备完成回调 → startInstallation → ADB 脚本生成 → StorageManager 写入；仅将外部存储目录查询暂停，在用户取消后返回 null。IOException 来自真实生产写入方法；最终断言要求已停止的任务释放 gate，在此 head 失败。未测真实设备上的存储故障发生率。

results.log 保存完整编译警告及运行输出。
