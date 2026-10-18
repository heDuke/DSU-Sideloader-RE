# wiring-probes（重试入口接线探针）

改编自 `docs/grok-handoff` 分支 `docs/reviews/pr2-fifth-review-repro/`（第五轮复审工具，提交 22fd974）。
做法不变：把 `HomeViewModel` 中相关方法按正则**原样**抽取到 `review3.HomeWiringHarness`，
Android 边界（Log、Session、StorageManager、PrivilegedProvider/IPrivilegedService、OperationModeUtils、libsu Shell 等）
用 `stubs/` 替代，再与生产代码 `InstallTaskGate` / `TaskSteps` / `RetryEntry` / `DsuSubmission` 一起编译运行。
这**不是** Android ViewModel / 生命周期 / Binder 集成测试。CI（`.github/workflows/android.yml` 的 "Wiring probes" 步骤）会运行它。

## 运行

```sh
PATH=/path/to/jdk17/bin:$PATH python3 tools/wiring-probes/run_wiring_probes.py .
```

依赖 jar 从 Maven Central 下载到临时目录；退出码 0 表示全部通过。

## 相对第五轮复审工具的改动

- `extract_wiring.py`：
  - 抽取列表中删除已移除的 `currentOrNewTask`，新增 `createRetryEntry`、`onClickRetryInstallation`、
    `onClickUnmountSdCardAndRetry`、`onClickSetSeLinuxPermissive`；
  - harness 前缀增加 `import com.topjohnwu.superuser.Shell`、成员 `retryEntry`（与 ViewModel 相同的 `by lazy { createRetryEntry() }`）；
  - harness 的 `viewModelScope` 增加 `CoroutineExceptionHandler`，逃逸异常收集到 `uncaught`，探针断言其为空。
- 新增 `stubs/Shell.kt`（libsu `Shell.cmd(...).exec()` 的可注入替身）。
- 新增 `RetryWiringTest.kt`：重试入口探针（ADB 脚本写入失败、参数读取失败、重复点击、SELinux 补救延迟中取消且清理完成后才解锁、DSU 应用阶段重试不并行提交且取消顺序保持、下一任务可开始）。
- `run_fifth_review.py` 重命名为 `run_wiring_probes.py`：基线增加 `RetryEntryTest`，编译并运行 `review3.RetryWiringTest`，其退出码计入总退出码。
- 未提交生成文件 `HomeWiringHarness.kt` 与 `results.log`（每次运行时重新生成/输出）。
- `FourthReviewWiringTest.kt`、`FifthReviewWiringTest.kt` 及其余 stubs 与原工具一致。

## PR #3 首轮审查（RE1）之后的改动

- `extract_wiring.py`、`run_wiring_probes.py`、`RetryHistoryReviewTest.kt` 原样取自 `docs/grok-handoff` 分支的
  `docs/reviews/pr3-retry-review-repro/`（提交 ac15641）。改动包括：额外抽取真实的 `remountSdCardsIfOwnedBy`
  （配合真实 `SdCardRemounter`），`refreshStorageRestorePending` 用空替身，`persistHook` 注入持久化故障，以及
  `retryJob` / `submissionJob` / `pendingRecord` 读取方法。
- 随后只在 runner 中追加了本仓库的 `RetryHandoffWiringTest.kt`：
  - 之前已交接 + 重试在交接前被取消；
  - 之前已交接 + 重试持久化失败；
  - 从未交接的取消仍会恢复存储（正向用例）。

  每个用例都断言：没有 mount、SYSTEM_UNKNOWN 记录保留、只有一个终态、下一任务的行为正确、没有协程逃逸异常。

## PR #3 二轮复审（RE2）之后的改动

- 原样取自 `docs/grok-handoff` 分支 `docs/reviews/pr3-rereview-repro/`（提交 5b9e32c）的文件：
  - `extract_wiring.py`、`run_wiring_probes.py`；
  - `RecoveryReviewTest.kt`、`LoggingHandoffReviewTest.kt`；
  - `stubs/`，其中新增 `CmdRunner.kt`，`Service.kt`、`Session.kt`、`AppPrefs.kt` 有调整。

  改动内容：抽取真实的存储恢复状态刷新与卡片操作方法、真实 `startLogging`，并编译真实 `LogcatDiagnostic`。
- 之后只在 runner 中追加了本仓库的 `LoggingHandoffWiringTest.kt`。场景：普通入口在交接后日志初始化抛异常。断言：
  - 不自动 mount；
  - SYSTEM_UNKNOWN 记录保留；
  - 只有一个终态 `INSTALL_SUBMITTED_UNCONFIRMED`，并带错误详情；
  - 显式恢复可用，且只有显式恢复会 mount；
  - 下一任务可开始；
  - 没有协程逃逸异常。
