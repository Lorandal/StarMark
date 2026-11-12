# StarMark 星拓

StarMark 是一款以本地处理为主的 Android 照片水印工具，支持拍摄参数、预设型号、文字与图片签名，以及自定义水印模板。

当前版本：**Beta.0.9.0.52_B6FE451**。最低支持 Android 13。正式版尚未发布。

## 下载与安装

- [GitHub 下载](https://github.com/Lorandal/StarMark/releases/tag/Beta.0.9.0.52_B6FE451)
- [GitLab 下载](https://gitlab.com/Lorandal/StarMark/-/releases/Beta.0.9.0.52_B6FE451)

大多数手机选择 `arm64-v8a.apk`，其他设备按架构选择。下载后可使用同名 `.sha256` 文件或 `SHA256SUMS.txt` 核对文件完整性。

Beta 与正式版使用 `com.realize.starmark`，后续同签名升级可保留数据。内部 Dev 使用不同包名，可与 Beta 并存；安装 Beta 不会自动搬移 Dev 中的草稿与设置。

## 功能

- 内置 75 款、11 类水印，支持导入外部模板与 StarUW 模板包。
- 提供 149 款预设型号，支持文字与图片签名、自定义参数图标。
- 支持最多 20 条草稿、撤销重做、逐模板记忆和批量处理。
- 关于页可手动检查 GitHub 与 GitLab 发布版本，按当前安装架构选择下载。
- 支持原尺寸照片导出；HDR、Live 与可逆编辑信息的保留取决于原文件结构及设备能力。
- 网页工作台用于设计和导出模板，照片处理在 App 中完成。

自动移除相机水印属于实验功能，仅在照片具有可验证的恢复数据时处理。JPEG 重新编码会产生编码误差；不同厂商的 HDR、Live 格式与显示效果可能有差异。

用户可见变化见[更新日志](update.md)。

## 从源码构建

项目使用 Flutter / Dart、Kotlin / Compose 和 Android 原生媒体处理组件。字体素材不随公开源码分发；自行构建前，需要取得相应字体授权，并按 `pubspec.yaml` 的字体配置准备资源。安装 Flutter、JDK 和 Android SDK、配置相应环境变量后，先将 `version.properties` 改为开发渠道（`channel=Dev`，迭代号使用 `0..49` 或 `100..499`），再在项目根目录执行：

```powershell
./scripts/flutter.ps1 pub get
./scripts/flutter.ps1 build apk --debug
```

维护者发布证书不随源码提供。自行构建使用本地开发签名，不能覆盖安装官方发布包。

`Beta` 保存公开测试源码，`Release` 暂存正式版发布说明。版本按四段数字顺序比较；例如 `Beta.1.0.1.55` 转正式版为 `Release.1.0.2.0`。安装包版本末尾为构建快照摘要前七位大写字母和数字；公开源码附件因排除内部说明文档，使用单独的 SHA-256 校验值。

## 许可与归属

字体、素材及第三方组件遵循各自许可，相关许可文件随源码保留。源码公开不改变第三方资源的许可范围。
