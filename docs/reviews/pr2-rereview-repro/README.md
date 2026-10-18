# PR #2 二轮复审复现材料

固定源码版本：`0ef87b30a9e98b44b8f1b891d78a8908c20c3712`。
报告：`docs/reviews/PR2-0ef87b30.md`（交接分支）。

此目录不属于应用生产代码，不应合并到应用源码或把隔离替身作为正式测试依赖。
实际编译指定 checkout 的 pipeline、sparse parser、remounter、task gate/outcome、DsuInstallationHandler 和 PrivilegedProvider。
`stubs` 仅提供 Android/IPC/偏好读写的最小边界。

## 运行

需要 Python 3.8+，带编译器模块的 Java 17/21，以及访问 Maven Central 的网络。
脚本下载固定版本依赖，在临时目录编译，不修改指定 checkout。

```sh
git worktree add --detach ../dsu-pr2-rereview 0ef87b30a9e98b44b8f1b891d78a8908c20c3712
python3 /path/to/pr2-rereview-repro/run_rereview.py ../dsu-pr2-rereview
```

也可用 `--deps /path/to/existing/jars` 复用上一轮工具下载的依赖。

此 head 的预期结果：项目 91 tests passed，四个新增 probe 失败，脚本返回非零。

## 四项证据的精确范围

- RR1：生产 InstallTaskGate + 与 HomeViewModel 相同的协程 try 边界，证明偏好读取挂起处取消后 terminal=false/busy=true。不是实例化真实 Android ViewModel；修复应补真实协调层/生命周期测试，而不能只改此模型。
- RR2：实际 DsuInstallationHandler 和实际 PrivilegedProvider + 可控存储读取，按 ViewModel 的取消顺序执行。取消结束后实际 startActivity 仍发生。不是完整 Android ViewModel/Binder/设备测试。
- RR3：直接执行生产 RemountPolicy，boot 变化加 INSTALLING 仍返回 Remount。
- RR4：直接执行生产 SdCardRemounter，mount 返回但查询挂载状态抛错，仍删除 pending。

`existing-tests.log` 和 `regression-tests.log` 来自独立执行的原始输出。四个 probe 的失败是发现缺口的证据，不代表整个项目原有测试失败。
