# DSU Sideloader：Grok Bot 开发交接与独立审查要求

状态：本文件是开发任务说明，由用户在 App 中交给 Grok Bot；提交交接说明不代表机器人已接单。

上游：https://github.com/VegaBobo/DSU-Sideloader
规划基线：master，70cb6da9ecf9ae19c72ea680b02f1a0e20c77653。开始开发前重新核对并记录实际基线。
目标仓库：https://github.com/heDuke/DSU-Sideloader-RE
上游关系：目标仓库是 VegaBobo/DSU-Sideloader 的 fork。
交接分支：docs/grok-handoff；文档路径：docs/GROK_HANDOFF.md。开发 PR 的目标分支为目标仓库的 master。

## 接单后立即执行

1. 阅读本说明并检查目标仓库的 AGENTS.md 等适用指引。
2. 从目标仓库最新 master 创建 fix/p0-installer-correctness 分支；不在上游仓库提交。
3. 先复核 P0 问题，建立可重复的构建与测试基线，然后实现最小正确性修复。
4. 创建面向 heDuke/DSU-Sideloader-RE:master 的 Draft PR，附测试结果、head SHA 和未验证事项。
5. 返回 PR 链接，交由本对话中的审查助手进行独立审查；不要声称已经完成独立审查。
6. P0 审查通过后，按后续阶段继续；若首批范围过大，将 P0 拆成数个可独立验证的 PR。

交接文档可通过 docs/grok-handoff 分支直接阅读；实现分支以最新 master 为基线。开发方需先确认自身具备目标仓库的分支和 PR 写入权限。

## 目标与边界

在 Kotlin + Jetpack Compose 基础上完成下一代 DSU Sideloader：

1. 使用 Material 3 Expressive 重设计应用。
2. 增加镜像库、安装预设、高级自定义与诊断能力。
3. 提供更可靠的侧载调度、多后端和生命周期恢复。
4. 建立自有 DsuLoader Engine，负责解析、校验、规划、安装协调和启动管理。

实际分区创建和启动切换仍由 Android DSU/gsid 完成。自有加载器不指自行实现 bootloader。
不得假设普通 Shizuku shell 或 system app 身份拥有 MANAGE_DYNAMIC_SYSTEM。
不自动修改 AVB、刷写 vbmeta 或关闭 SELinux。
多槽位、替换 gsid 和额外系统组件属于独立实验范围，不作为稳定版前提。

## 提交方式

- 在指定目标仓库创建独立分支，提交 Draft PR；不要直接修改或合并主分支。
- 按以下阶段提交小而可审查的 PR，优先交付 P0；后续阶段依赖前阶段审查结果。
- 每个 PR 记录基线和 head SHA、具体行为变化、验证命令、结果与已知限制。
- 明确区分静态检查、模拟测试、模拟器测试与真机测试，不得编造设备覆盖。
- UI PR 附浅色、深色、大字体和宽屏截图；核心安装 PR 附失败路径证据。
- 保留许可证和原作者署名，不上传签名密钥、凭据或完整敏感日志。
- 本说明不授权自动合并或发布版本；代码需经过独立审查。

## P0：安装正确性与工程基线

先复核以下源码观察，并为确认的问题提供最小修复和有意义的回归测试：

- installer/root/DSUInstaller.kt 的 shouldInstallEntry 使用 substringAfterLast(".")，提取得到扩展名而非分区名，过滤不符合预期。使用规范化路径和分区允许列表，拒绝非法路径、重复分区和不支持的分区。
- 检查 startInstallation、createPartition、setAshmem、submitFromAshmem、closePartition、finishInstallation 的结果。任一步失败应终止后续流程，不能触发成功。
- 将错误、取消与清理转为可传播的明确结果；避免仅调用错误回调后继续提交。
- 修复无日志权限时部分路径直接显示成功的行为，区分已提交系统、等待操作、无法确认和确认完成。
- 正确处理 gzip ISIZE 的模数语义、未知展开大小、截断内容和大文件计数。
- 检查流、文件描述符与共享内存的资源释放及异常路径。
- SD 卡恢复与任务状态关联，持久化待恢复信息，不能只依赖固定延时。
- 将格式化与 CI 校验分开，检查任务不应自动修改源码。

基线交付：可重复构建、已知问题清单、设备/权限/格式能力矩阵、上述修复及测试。
工具链分批升级；compile SDK 与 target SDK 行为变化分别验证，保留 Android 10 兼容目标。

## P1：自有加载器与可靠任务模型

建议模块边界：

- SourceResolver：本地 URI、文档提供方、下载与授权。
- ImageInspector：raw/sparse、压缩格式、分区和尺寸识别。
- PackageValidator：包清单、哈希、分区名称与元数据验证。
- InstallPlanner：生成不可变 InstallPlan，含镜像指纹、后端、权限、空间和步骤。
- InstallCoordinator：状态机、全局写入互斥、取消、错误和清理。
- InstallerBackend：系统 DSU、Shizuku shell、Root 直连、ADB 辅助和经过验证的系统集成。
- BootController：查询、启用/停用、单次/持续启动及重启请求。
- RecoveryManager：持久化任务与真实系统状态核对。
- DiagnosticsCollector：结构化事件与限定范围日志。

要求：

- UI/ViewModel 不拥有长任务的唯一生命周期。
- 状态包含检查、准备、等待授权/确认、分配、写入、提交、核实、完成、取消中、清理中、失败和未知。
- 持久化任务和临时文件归属；应用重开时核实系统状态，禁止仅凭旧进度自动继续写入。
- 写入前固定后端；开始写入后不静默切换后端。
- 下载和准备步骤可在条件满足时恢复；不得承诺系统 API 未支持的分区写入续传。
- 特权 Binder 接口校验调用者与参数；隐藏 API 集中在版本适配层。
- 有界缓冲和流式处理，检查尺寸溢出、截断、ZIP 路径及重复条目。
- Android 后台任务、前台服务类型和超时规则按版本验证，不承诺进程永久存活。
- 删除、重启和持续启动提供明确确认；自动重试仅用于可证明安全的步骤。

## P2：Material 3 Expressive 重设计

主导航：概览、镜像库、任务、工具；设置、帮助、关于通过应用栏进入。
窄屏底部导航，宽屏导航栏/侧栏加双栏详情。

安装流程：选择镜像 → 环境与文件检查 → 参数配置 → 方案确认 → 准备/安装 → 核实结果。
已有 DSU 优先呈现启动和管理入口，替换已有安装使用独立流程。

设计规范：

- Expressive 形状、排版、色彩角色、分组按钮、层次与动效；建立统一设计令牌。
- 动态配色、固定品牌主题、浅色与深色模式。
- 主操作显著，日志和低频选项渐进展开，避免同权重卡片堆叠。
- 分别表达未知耗时、确定进度、等待确认与无法查询。
- 动效解释状态变化并尊重系统动画偏好；不妨碍持续进度阅读。
- 实验 API 封装在设计系统内，使用实际依赖版本可用的组件。
- TalkBack、大字体、48dp 最小触控区域、文字与图标共同表达状态。
- 交付全部页面及空、错误、权限缺失、断连、恢复和已有 DSU 状态。

## P3：自定义、镜像库与诊断

日常、高级、实验选项分层，注明适用后端、默认值与影响：

- 镜像来源：本地/SAF/HTTPS；默认完整下载并校验后安装。
- 镜像元数据：名称、来源、备注、标签、收藏、版本、预期 SHA-256。
- userdata：快捷容量、手动输入、设备限制与实时空间预算。
- 镜像大小：自动计算优先，手动覆盖展示差异。
- 后端：自动推荐或选择实际可用后端，说明限制。
- 缓存：工作目录、保留和清理策略；区分缓存位置与最终 DSU 分区位置。
- 下载：Wi-Fi、保守并发、重试、断点恢复；恢复前验证远端文件身份。
- 预设：保存/复制/导入/导出，参数校验、绑定镜像指纹，不含授权或可执行脚本。
- 启动：单次、持续、关闭持续；按平台与权限开放。
- 外观、通知、亮屏、动效偏好和日志级别。
- 诊断中心：错误码、阶段、返回值、事件时间线、导出预览与脱敏。

继续支持 img/gz/xz 与符合要求的 DSU ZIP。自有包清单可以增加版本、分区、展开尺寸、哈希和可选签名。
完整性校验与来源可信度分开呈现；通用 OTA/刷机 ZIP 不能仅凭包含 img 就导入。
多个镜像存在库中不代表支持多个可启动 DSU 槽位。

## 验证与审查门槛

- 解析：raw/sparse、大文件、未知大小、损坏/截断压缩包、非法路径和重复分区。
- 调度：每种失败返回、取消、重复触发、互斥、清理和安全重试。
- 故障注入：空间耗尽、URI 失效、Binder/网络断开、进程终止。
- UI：全部任务状态、主题、大字体、TalkBack、窄/宽屏。
- 真机：权限后端、代表 Android 版本、厂商与文件系统；公开实测矩阵。
- 发布前验证安装、取消、重装、启用、启动、移除和迁移。
- 所有注入的安装失败不能显示成功；未知状态必须明确可见。
- 内存不随镜像大小线性增长，主线程不进行文件处理或阻塞 Binder 等待。

独立审查输出：按严重程度列出具体问题、文件/行号、触发条件、影响与修改建议；单列未验证范围。
复审时重新固定 head SHA，核对修复与新增回归，未通过核心可靠性检查不得建议合并。

## 里程碑、排期与交付

估算条件：一名熟悉 Android 的开发者，配合设计评审和真机测试资源；总计约 12–16 周。
这是工作量估计，不表示机器人能在无人参与的情况下完成真机测试或持续运行到交付。

| 阶段 | 估计周期 | 退出条件 |
| --- | --- | --- |
| 调研与 P0 基线 | 第 1–2 周 | 基线可复现，确认的问题有修复和测试，形成权限/设备/格式矩阵 |
| 设计与技术验证 | 第 3–4 周 | Expressive 原型、自有加载器接口、各后端能力验证完成 |
| 加载器核心 | 第 5–7 周 | 解析、规划、互斥、状态机、写入检查、取消与清理通过审查 |
| 新界面与自定义 | 第 8–10 周 | 镜像库、安装流程、预设、启动管理和诊断主要流程贯通 |
| 下载与恢复 | 第 11–12 周 | 下载校验与恢复、进程恢复及后台行为验证完成 |
| Beta 与稳定化 | 第 13–16 周 | 真机矩阵、迁移、性能、文档和候选 APK 完成 |

第 4 周为可行性检查点：固定各权限模式的真实可交付能力与稳定版范围。
先交付正确性修复，再交付新引擎及界面。不要把工具链、界面、安装器重写堆在一个 PR。
Alpha 聚焦本地镜像及核心流程，Beta 增加下载与恢复，稳定版以实测矩阵为发布依据。

最终交付：设计规范、能力矩阵、引擎与后端接口说明、源码、测试报告、APK、迁移及故障处理文档。
若使用独立应用身份发布，规划包名和更新渠道；原包名原地升级需要匹配原签名，不得假设 fork 拥有原签名。
迁移覆盖偏好、工作目录授权和临时文件；不得自动删除用户原始镜像。

## 参考源码

- https://github.com/VegaBobo/DSU-Sideloader/blob/master/app/src/main/java/vegabobo/dsusideloader/installer/root/DSUInstaller.kt
- https://github.com/VegaBobo/DSU-Sideloader/blob/master/app/src/main/java/vegabobo/dsusideloader/ui/screen/home/HomeViewModel.kt
- https://github.com/VegaBobo/DSU-Sideloader/blob/master/app/src/main/java/vegabobo/dsusideloader/core/StorageManager.kt
- https://github.com/aosp-mirror/platform_frameworks_base/blob/main/services/core/java/com/android/server/DynamicSystemService.java

AOSP 当前代码仅用于理解边界，实际行为须对照目标 Android 版本与 OEM 实现验证。
