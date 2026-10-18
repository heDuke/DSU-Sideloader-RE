# PR #2 第四轮独立复审：cc6aec38

结论：TR1/TR2 可按源码与 JVM 回归关闭。另有一项确定的异常收尾缺陷 FR1（P1），建议修复后再合并，PR 继续保持 Draft。本轮未修改应用源码、未合并、未提交 GitHub 正式评审。

- PR：https://github.com/heDuke/DSU-Sideloader-RE/pull/2
- 固定 head：`cc6aec389d3c187249243783bb70eab9fe261176`
- 对比版本：`9090fc71704d37789d301330e68c1f3b913d5113`
- 本轮两个提交：`1c37a9b`、`cc6aec3`，共 11 个文件。

## 独立验证

- GitHub check-runs 确认此 head 的 build=success：https://github.com/heDuke/DSU-Sideloader-RE/actions/runs/39099072057/job/117591420264 。
- 独立 JVM 编译相关生产代码和全部 13 组项目测试：125 个通过。
- 适配后的 TR1/TR2 两项接线 probe 均通过。
- 新增 ADB 异常收尾 probe 失败：任务 Job 已完成，但 gate 仍被占用、卡片停在 CANCELLING、后续任务被拒绝。
- `git diff --check 9090fc71..HEAD` 通过；应用 checkout 无修改。
- 未独立运行完整 Gradle Android 构建、Android Lint、模拟器或真机。作者报告的 assemble/Lint 结果不作为本地执行结果。

测试方式：从当前 head 原样提取 16 个 HomeViewModel 方法和 StorageManager.writeStringToExternalFileDir，运行真实的 TaskSteps、InstallTaskGate、DsuSubmission、DsuInstallationHandler、PrivilegedProvider、AdbInstallationHandler、GenerateInstallationScript、InstallationCmdline 等代码。框架 scope、Session、偏好、准备文件 I/O、权限/Settings、Intent、存储上下文等边界使用替身。**不是完整 Android ViewModel/生命周期/Binder 集成测试。** 新 probe 的存储异常由生产写入方法自身抛出；替身只控制 getExternalFilesDir 的延迟和 null 返回值，没有在 onTaskFailed 或 gate 中复制故障逻辑。

开发者所述 `/workspace/dsu-review/PR2-cc6aec38.md` 不在本次环境中；本报告基于源码及独立测试，不声称读过该本地文件。

## TR1/TR2 结论与工具适配

| 项目 | 结果 |
| --- | --- |
| TR1 | 准备未交接时的回调先清理任务目录，再 finish；清理失败进入结果。TaskWorkspaces 生产测试覆盖任务目录隔离。阻塞实际提取的回调所调用的清理边界时，新任务被拒绝，测试文件未被删除。 |
| TR2 | Job 归属 task；DSU_APP 阶段与 dsuSubmission 注册在同一次 gate 锁中完成；提交侧校验身份。取消发生在注册前时 gate 保持占用，旧调用退出后才能开始 B，未发生旧任务 forceStop/startActivity。 |

旧工具不能原样编译或按旧的解锁时机等待，这是接口和协议改变的正常结果。适配保留拒绝旧提交的断言：

- 移除 harness 中的共享 installationJob，使用真实 taskGate.jobOf；补上新的 TaskWorkspaces 依赖及 taskKey 清理接口。
- TR1 的存储替身模拟任务范围内清理；任务目录隔离本身由生产 TaskWorkspaces 测试验证。接线 probe 继续断言清理阻塞时 B 不能开始，并保留文件存活断言。
- TR2 不再等待“注册窗口内 gate 释放”：改为在该窗口尝试 B、明确断言被拒；放开 A 后等待实际退出，再开始 B。保留禁止旧 startActivity，并补充禁止 forceStop 的断言。
- 原样提取方法的脚本和生成结果一并附上。

实测：

```text
OK (125 tests)
FOURTH PROBE preparation cleanup: newTaskAdmitted=false newTaskFileDeleted=false
FOURTH PROBE submission publication: [cancel-finished-next-task=2]
OK (2 tests)
```

## FR1 · P1：准备已 settle、后端尚未接管时，取消叠加脚本 I/O 失败使任务永久停在取消中

定位：

- [HomeViewModel.kt:365–380](https://github.com/heDuke/DSU-Sideloader-RE/blob/cc6aec389d3c187249243783bb70eab9fe261176/app/src/main/java/vegabobo/dsusideloader/ui/screen/home/HomeViewModel.kt#L365-L380)：先 settle 再调用完成回调；交接异常进入 onTaskFailed。
- [HomeViewModel.kt:425–451](https://github.com/heDuke/DSU-Sideloader-RE/blob/cc6aec389d3c187249243783bb70eab9fe261176/app/src/main/java/vegabobo/dsusideloader/ui/screen/home/HomeViewModel.kt#L425-L451)：ADB 分支同步生成脚本，在生成成功的回调中才 finish，期间仍为 PREPARING。
- [HomeViewModel.kt:601–615](https://github.com/heDuke/DSU-Sideloader-RE/blob/cc6aec389d3c187249243783bb70eab9fe261176/app/src/main/java/vegabobo/dsusideloader/ui/screen/home/HomeViewModel.kt#L601-L615)：PREPARING 的取消只 cancel Job，依赖准备步骤报告结果。
- [HomeViewModel.kt:691–695](https://github.com/heDuke/DSU-Sideloader-RE/blob/cc6aec389d3c187249243783bb70eab9fe261176/app/src/main/java/vegabobo/dsusideloader/ui/screen/home/HomeViewModel.kt#L691-L695)：isCancelling 时直接返回。
- [StorageManager.kt:133–142](https://github.com/heDuke/DSU-Sideloader-RE/blob/cc6aec389d3c187249243783bb70eab9fe261176/app/src/main/java/vegabobo/dsusideloader/core/StorageManager.kt#L133-L142)：externalFilesDir 不可用、创建/写入失败均可抛 IOException。

调用链：

1. Preparation 完成，onConfirmInstallationSheet 的 step.settle() 已成功。
2. onPreparationFinished → startInstallation → setupAdbInstallation → GenerateInstallationScript.writeToFile。文件生成尚未完成，gate.phase 仍为 PREPARING。
3. 用户取消：任务被标记 cancelling，Job 被取消；PREPARING 分支不启动独立收尾。
4. 同步文件 I/O 随后失败。TaskSteps 捕获异常，因 step 已 settle，只调用 onErrorAfterSettle。
5. onErrorAfterSettle 调用 onTaskFailed，但后者见 cancelling 直接返回。
6. 协程完成回调因已 settle 不再调用 onUnsettled。没有其他路径负责 finish，任务锁留住，界面永久停在取消中，无法再开始安装，直至重新创建协调器/进程。

该缺陷不限于备注中“DSU_APP 切换前且几乎不抛异常”的参数捕获窗口。ADB 脚本生成包含真实的同步存储 I/O；外部目录暂不可用或写入失败是需要处理的输入条件。本轮没有证实它在真机上的发生频率，也不声称观察到了真实设备故障，但异常下的状态机结果已用生产方法复现。

复现用例 `HandoffExceptionTest.cancellationDuringSettledHandoffMustStillReleaseGate`：

- Session 选择 ADB；准备替身立即正常完成。
- 真实调用走到脚本写入的 getExternalFilesDir 边界时暂停。
- 调用提取的真实取消方法，然后让该边界返回 null，触发真实 StorageManager 方法中的 IOException。
- 等待任务 Job 真正完成后检查 gate 和 UI。

实测：

```text
FOURTH PROBE ADB storage unavailable during cancel:
jobCompleted=true gateBusy=true nextTaskAdmitted=false card=CANCELLING
FAILURES!!! Tests run: 1, Failures: 1
```

建议最小修复：

- 将“步骤已完成”与“下一后端已接管并拥有终态责任”区分，确保交接失败且取消时仍有唯一的收尾者。
- 交接失败时，如果任务尚未进入需要等待后端停止/清理的状态，走该任务自己的清理及取消终态路径；等待操作停止和清理完成后才 finish。
- 不要全局删除 onTaskFailed 的 cancelling 保护：已经进入 DSU_APP/ROOT_INSTALL 的任务仍需等待真实后端停止与清理。
- 补充实际 onConfirm → ADB 脚本生成接线的回归；覆盖取消前/后抛 IOException，保证终态一次、gate 释放、旧写入停止后才能开始 B。保留已通过的 TR1/TR2 断言。

## 非阻塞备注及设备验证

- 回收旧任务临时 .gz 本身未在本轮单列 P1。不过“完全沿用旧行为”表述不够准确：此前 root 准备直接分支返回，现在 removeStaleWorkspaces 位于 root 分支之前，root 路径也新增了回收；设备验证应检查重试/复用输入和旧平铺布局的清理边界。
- SAF 子目录创建、递归删除、DSU 系统应用能否读取子目录镜像仍需设备验证。
- 无 root 取消后的实际残留、Binder/生命周期接线、root/Shizuku/ADB 各模式仍需验证。
- 保持既有建议：稳定版前若跨重启自动挂载没有可靠设备证据，默认提示用户恢复。当前报告不再重开 RR3/RR4。
- 不扩展到新加载器、下载引擎、Material 3 Expressive 重设计或翻译清理。本轮后续只需修 FR1 并补回归。

## 交给开发者

TR1/TR2 可以关闭。请在 PR #2 单独修 FR1：准备 step 已 settle、ADB 脚本 I/O 尚未完成时取消，随后 I/O 失败，没有终态收尾者。按配套用例覆盖真实交接路径，保留任务目录隔离、清理完成后解锁和提交身份校验。提交新的 head、CI 和验证边界；继续 Draft，不合并。
