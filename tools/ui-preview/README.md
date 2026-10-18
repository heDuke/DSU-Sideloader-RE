# 无模拟器的界面预览

`run_preview.py` 将源码复制到新目录，仅在副本中加 Paparazzi 1.3.5。渲染生产 `DashboardLayout`、`ExpressiveInstallPane` 和 `ImageLibraryPanel`，包含官方 Expressive 主题、形状、导航及主按钮。生产依赖不加预览插件，不启动模拟器，不需要 KVM。

要求：JDK 17、项目 Android SDK、Python 3、可下载 Gradle/Maven 依赖的网络。按本项目正常方式配置 JAVA_HOME / ANDROID_SDK_ROOT / GRADLE_USER_HOME。

```sh
python3 tools/ui-preview/run_preview.py . --workdir /tmp/dsu-preview-copy --output /tmp/dsu-preview-images
```

输出为空安装页、已选镜像页、镜像库的中文预览；每页有浅色、深色、200% 字体和宽屏，共 12 张。另含源码来源、脏工作区标志和 app/src/main 内容摘要。没有既有像素基线时，生成成功只说明完成渲染，需人工查看布局。

固定预览数据、无副作用的按钮回调和 Android activity-result registry 为替身。不会安装 DSU、打开真实文档或调用 Binder。静态截图不能证明动效手感、底部面板的操作时序、ViewModel 接线、TalkBack、滚动触摸、真实窗口/系统栏或生命周期已通过设备验收。
