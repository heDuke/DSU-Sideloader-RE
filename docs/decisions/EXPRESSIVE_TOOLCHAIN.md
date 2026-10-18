# Expressive 组件工具链

为了使用官方 `MaterialExpressiveTheme`、`MotionScheme.expressive()`、形变按钮、短导航栏、宽导航栏及 LoadingIndicator，升级工具链，单独评审。界面源码在后续 PR。

| 组件 | 版本 |
| --- | --- |
| JDK / Gradle | 17 / 8.9（wrapper 校验 SHA-256） |
| Android Gradle Plugin | 8.7.3 |
| Kotlin / Compose compiler plugin | 2.1.21 |
| Compose UI / Material 2 兼容组件 | 1.8.2 |
| Compose Runtime | 1.9.0 |
| Material 3 | 1.5.0-alpha10 |
| Hilt | 2.56.2 |
| compileSdk / targetSdk / minSdk | 35 / 33 / 29 |

Material 3 1.4.0 稳定版没有本次所需的 LoadingIndicator 和连接按钮形状 API。1.5.0-beta01 要求 compileSdk 37、AGP 9.1；本次固定 alpha10，避免将 AGP 9 迁移混入视觉重做。实验 API 的变化风险保留在 Draft 中；升级或发布前必须重新验证。并未将实验版称为稳定版。

用 Gradle 原生版本目录取代 refreshVersions，Kotlin Compose 编译器插件跟随 Kotlin。原 Kotlinter 插件把旧 Kotlin 编译器装进 Gradle 类路径，与 Kotlin 2 冲突；改用隔离 JavaExec 跑原有 ktlint 0.48.2，保持规则不变，检查和格式化继续分开。该 CLI 的旧 PSI 需要限定在这两个子进程里的 Java 17 `java.lang` opens 参数。

compileSdk 35 将 SigningInfo 标记为可空；签名读取遇到 null 返回不可确认，不强制解包或信任构建。targetSdk/minSdk、应用身份、版本号、安装及任务策略不变。Android 10 运行情况仍需设备验证。

官方依据：
- https://developer.android.com/jetpack/androidx/releases/compose-material3
- https://developer.android.com/develop/ui/compose/designsystems/material3
- https://m3.material.io/blog/building-with-m3-expressive

本地构建和纯 JVM 测试证明编译/测试情况，不证明 Android 10、真实 DSU 或运行时动画已验收。无 KVM，不启动模拟器。

验证：`lintKotlin`、`testDebugUnitTest`（165 项，0 失败）、`assembleDebug`、`assembleMiniDebug` 通过；`formatKotlin` 后再次 `lintKotlin` 通过。新工具链仍有旧 API 弃用提示。Android Lint 和设备行为另行记录，不将其列为通过。
