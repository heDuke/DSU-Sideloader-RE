# PR #2 独立 JVM 复现材料

审查版本：`03d11904b5a3fdbc12dab2089c7abe40b301d433`。
完整报告：同一交接分支下的 `docs/reviews/PR2-03d11904.md`，含 R1–R6 的位置、证据、建议及六项产品取舍。

本目录是隔离的审查工具，不是 Android 设备测试，也不属于应用的生产源码。
`stubs` 仅替换 Android Log、DataStore 和特权服务接口。实际被测试的 pipeline、分区策略、gzip 计数和 remounter 均从指定 checkout 原样编译。
不要把这些替身复制进应用源码。正式回归测试应使用项目实际接口和适合的测试依赖。

## 运行

需要 Python 3.8+、带编译器模块的 Java 17 或 21，以及访问 Maven Central 的网络。
脚本会下载固定版本的 Kotlin 编译器/JUnit/相关依赖，在临时目录编译，并打印实际源码 head。

在含有完整 Git 历史的目标仓库中，为原审查版本建立隔离 checkout：

```sh
git worktree add --detach ../dsu-pr2-reviewed 03d11904b5a3fdbc12dab2089c7abe40b301d433
python3 /path/to/repro/run_review.py ../dsu-pr2-reviewed
```

也可直接将新修复版本 checkout 的路径传给脚本复核；新版本若改变接口，需相应更新隔离替身，不能把编译失败算作修复成功。

## 原版本预期结果

- 现有四组测试：45 passed。
- `ReviewRegressionTest`：3 failures，分别暴露 R1、R4、R5，因此脚本返回非零状态。
- 第一个 probe 用普通 DEFLATED ZIP 封装合法 sparse 镜像，头部足以提供大小，但 pipeline 拒绝。
- 第二个 probe 向实际 remounter 注入 mount 异常，验证失败卷应保留 pending；实际记录被清空。
- 第三个 probe 构造块总数/块记录矛盾且第二 RAW payload 截断的 sparse；实际 pipeline 返回 Success 并调用 finish。

R2/R3 来自调用链审查，需另补任务/界面集成测试及设备时序验证。R6 是资源所有权审查，需补包装流关闭测试。
本材料不能证明真实 gsid、SD 卡、Binder 或启动行为，不能替代 Android 构建和真机验证。
