# ADBKit

一个简洁的 Android ADB 调试快捷工具：把开发者选项里常用的几个开关做成 App 与快捷磁贴，点一下就切换。

## 功能

- **开发者选项** — 一键开关
- **USB 调试** — 一键开关
- **USB 安装** — 一键开关（支持 MIUI / HyperOS）
- **USB 调试安全设置** — 一键开关
- **无线调试** — 一键开关，显示当前可连接的 `IP:端口`（兼容 Android 17 / HyperOS 不写入 `service.adb.tls.port` 的机型）
- **系统页面跳转** — 设置页可直接跳转到系统的 USB 调试 / 无线调试 / 开发者选项页
- **快捷磁贴** — 5 个 Quick Settings Tile，下拉控制中心即可操作
- **长按磁贴打开应用** — 长按任意磁贴即可跳转打开 App
- **内存管理** — 遵循公平运行内存机制，磁贴操作完自动退出，不常驻后台

## 界面

- MIUI 风格 UI（[miuix](https://github.com/compose-miuix-ui/miuix) 组件库）：大标题折叠顶栏、圆角卡片、MIUI 风格设置项
- 底部栏三形态：标准 / 悬浮胶囊 / 液态玻璃（边缘折射透镜），设置内即时切换
- 液态玻璃的选中胶囊按 tab 精确对齐，四边等距；浅色 / 深色下的对比度各自校准
- 主题：跟随系统 / 浅色 / 深色，状态栏图标随深浅色自动适配

## 特性

- 磁贴操作后自动退出：触发 10 秒自杀计时；若被 SystemUI 重新拉起会重新武装计时，直到系统解除磁贴绑定后进程真正退出
- 使用保护：应用在前台或面板展开浏览磁贴时不会自杀，连续点击自动重置计时
- 液态玻璃 / 毛玻璃基于 RuntimeShader，设备不支持时自动降级为不透明样式
- 底栏下方的内容会先淡出成背景色，玻璃不会采到滚动的亮色文字
- 设置写入一律回读校验：写不进去会明确提示，不会出现"点了没反应"
- 轮询 10 秒一次；无线调试关闭时只读系统属性，不额外 dump adb 服务

## 要求

- Android 13+ (API 33)
- **Root 可选**：
  - 有 Root：全部功能可用；`USB 安装`、`USB 调试（安全设置）`、以及 Android 17 / HyperOS 上的端口读取依赖 Root
  - 无 Root：用 adb 授权下面两条权限后，开发者选项 / USB 调试 / 无线调试的开关和状态读取都能用（端口在部分机型上读不到，会显示"端口获取中…"）

```sh
adb shell pm grant com.lxithral.adbtools android.permission.WRITE_SECURE_SETTINGS
adb shell pm grant com.lxithral.adbtools android.permission.DUMP
```

## 安装

从 [Releases](../../releases) 下载 `ADBKit-release.apk` 安装。

> 1.0.5 起更换了签名密钥，从 1.0.4 及更早版本升级请先卸载旧版再安装。

## 构建

```sh
# 需要 JDK 21 与 Android SDK Platform 37
./gradlew assembleDebug     # 调试包
./gradlew assembleRelease   # 发布包（读取根目录 keystore.properties）
```

签名配置放在仓库根目录的 `keystore.properties`（不入库）：

```properties
storeFile=release.keystore
storePassword=******
keyAlias=******
keyPassword=******
```

推送 `v*` 标签会触发 GitHub Actions 自动构建并发布 Release，签名材料取自仓库 Secrets。
