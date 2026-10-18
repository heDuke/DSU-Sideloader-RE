# 官方 Material 3 Expressive 界面

按用户要求统一使用官方 Material 3 Expressive，尽量使用组件默认实现。应用自定义代码仅组织业务内容、绑定状态和回调，不重造控件外形、字体、按压反馈或加载动画。

## 组件选择

| 范围 | 官方实现 |
| --- | --- |
| 全局主题、动效、字号与形状 | `MaterialExpressiveTheme`、`MotionScheme.expressive()`、默认 `Typography()` / `Shapes` |
| 主按钮、文字按钮、次级按钮 | `Button` / `TextButton` / `FilledTonalButton` 的 Expressive `ButtonDefaults.shapes()` 重载 |
| 图标按钮 | `IconButton(shapes = IconButtonDefaults.shapes())` |
| 主导航 / 其他页面顶栏 | 带 subtitle 的 Expressive `TopAppBar` / `MediumFlexibleTopAppBar` |
| 底部导航 / 宽屏侧栏 | `ShortNavigationBar` / `WideNavigationRail` |
| 镜像来源 | 默认 `OutlinedCard` + `ListItem` |
| 参数、镜像库、工具、设置和许可证条目 | 默认 `ListItem` + `HorizontalDivider` |
| 容量和外观选择 | 官方 `ToggleButton`，连接选择使用 `ButtonGroupDefaults.connected*ButtonShapes()` |
| 开关、后端单选、输入 | 官方 `Switch`、`RadioButton`、`OutlinedTextField`，由统一 Expressive 主题提供样式 |
| 安装、权限、更新进度 | `LoadingIndicator` / `LinearWavyProgressIndicator` |
| 参数面板、确认、提示 | 官方 `ModalBottomSheet` / `AlertDialog`；标题使用原生 title 槽位，操作使用 Expressive 按钮 |
| 状态卡片、日志 | 默认 `Card`；日志使用 `SelectionContainer`，仅日志正文保留等宽字体 |

没有 Expressive 专用重载的组件直接使用同一主题下的官方 Material 3 实现，不自造一个“Expressive 版”。不混用 Material 2。

## 本轮收敛

- 删除花形图标底座、非对称来源卡片、手工拼接圆角设置组和自设计镜像卡片。
- 删除自行指定的字体大小/字重，按钮采用官方排版、尺寸和禁用状态；不再把文字强制铺到左边、箭头挤到最右。
- 设置、关于、更新、ADB、许可证、日志与确认弹窗也使用同一套主题和官方组件，不仅覆盖安装首页。
- `CardBox`、`PreferenceItem`、`ActionButton` 等保留为薄的业务组合，内部直接使用官方组件；不再实现自己的形状或点击反馈。
- 品牌色与动态配色仍为主题选项。错误采用 Material 语义色；不将未确认结果涂成成功。

## 布局和行为

840dp 起采用官方侧栏和安装双栏，主操作最大宽度 480dp。内容可滚动；按钮和导航的触控、无障碍语义由官方组件提供。200% 字体需要滚动查看全部内容。

文件选择、确认、安装、重试、取消和恢复操作仍走既有任务锁与身份检查。已提交/未确认、清理结果未知等状态继续如实显示，不用模拟进度替代真实结果。

## 实际组件预览

通过 layoutlib 渲染生产组件，共 20 张：空来源、已选来源、镜像库、安装校验状态、设置/关于组件组合；各含浅色、深色、200% 字体、真实横屏。设置/关于组合使用固定数据，并非运行完整 ViewModel 的设置页面。

<img src="previews/install-light.png" width="240" alt="浅色安装页" /> <img src="previews/install-dark.png" width="240" alt="深色安装页" /> <img src="previews/library-light.png" width="240" alt="原生列表镜像库" />

[200% 字体安装页](previews/install-large-text.png) · [200% 字体镜像库](previews/library-large-text.png) · [横屏](previews/install-wide.png) · [设置/关于组件](previews/native-components.png) · [安装校验状态](previews/active-task.png)

这些是生产组件的静态预览，文件选择、按钮回调和 activity-result registry 为替身；不证明面板触摸、键盘、TalkBack、真实动效、生命周期或 DSU 验收。无 KVM，不启动模拟器。来源摘要见 [origin.json](previews/origin.json)，复现方法见 [预览工具](../../tools/ui-preview/README.md)。

官方依据：
- https://m3.material.io/blog/building-with-m3-expressive
- https://developer.android.com/develop/ui/compose/designsystems/material3
- https://developer.android.com/jetpack/androidx/releases/compose-material3

实验 API 的版本选择和风险见 [工具链说明](../decisions/EXPRESSIVE_TOOLCHAIN.md)。
