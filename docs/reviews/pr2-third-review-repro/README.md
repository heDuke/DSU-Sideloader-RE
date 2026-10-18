# PR #2 三轮复审：生产方法片段接线测试

固定源码：`9090fc71704d37789d301330e68c1f3b913d5113`。
报告：交接分支的 `docs/reviews/PR2-9090fc71.md`。

这些不是完整 Android ViewModel/生命周期/设备集成测试。
`extract_wiring.py` 从指定 checkout 原样提取 12 个生产方法，生成 `HomeWiringHarness.kt`。
方法之外的外层 scope、Settings、存储、IPC、Intent 等边界使用最小替身；与此场景无关的日志和恢复挂载辅助调用为空替身。
实际的 TaskSteps、gate、submission、handler、provider 从生产源码原样编译。
提取结果可以逐字对照实际 HomeViewModel，以避免用改写过的测试模型冒充代码验证。

## 运行

需要 Python 3.8+、带编译器模块的 Java 17/21，以及 Maven Central 网络（或用 --deps 复用前两轮工具下载的 jars）。

```sh
git worktree add --detach ../dsu-pr2-third 9090fc71704d37789d301330e68c1f3b913d5113
python3 /path/to/pr2-third-review-repro/run_third_review.py ../dsu-pr2-third
```

runner 会重新提取生产方法，然后在临时目录编译，不修改 checkout 的应用源码。
工具自身的 HomeWiringHarness.kt 会更新为指定 checkout 的片段。
若生产方法重构，需相应适配提取边界；不能仅改替身或测试模型来宣称修复。

此 head 的结果：项目 114 个测试通过；新增 2 项接线断言失败，脚本返回非零。

- TR1：暂停旧任务的工作目录清理，实际终态回调已经开放新任务；旧清理恢复后删除新文件。
- TR2：在实际 startPrivilegedInstallation 的 currentBootId 边界暂停，执行实际取消方法并开始下一任务；旧调用恢复后借用下一任务 Job，实际 handler 调用 startActivity。

`existing-tests.log` 和 `wiring-tests.log` 是独立执行的原始输出，不表示完整 Android 构建或真机验证。
这些替身和工具只用于隔离审查，不应加入应用生产代码。正式回归应放到真正执行的协调层或 Android 测试中。
