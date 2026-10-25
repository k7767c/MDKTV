# MDKTV 原生 TV 工程

来源：<https://github.com/jimi10000/maidong-ktv>，提交 `2c9351ffda9a891991e059284bb90938c51d66ef`。原项目：<https://github.com/coolwuzizai/maidong-ktv>，原作者杨家三郎。保留上游 README、LICENSE 和源码注释。

该来源的全部文件原为 Base64 文本。本目录使用严格解码并验证重新编码一致的结果；路径、解码前后 SHA-256 和未导入文件列表见 `UPSTREAM-DECODE.json`。未导入机器专用 `local.properties`、根目录 UI 调试转储与截图。集成后的少量修改由 MDKTV Git 记录追溯。

## 已接入的功能

- 原生 TV 主界面、遥控器导航、歌曲管理和 IJK 播放器。
- 手机话筒页面和 PCM 传输、MicStreamServer、HttpsMicServer、AudioTrack 输出及抖动缓冲。
- 主界面已经通过 PCM 旁路接入 VocalScorer；对应原始单元测试一并导入。
- 原生均衡器、音频焦点和原唱/伴唱控制。

这是独立的原生应用工程，与 `android-tv/` 的 Node 服务 WebView 客户端、`tv/` 的通用网页客户端分别构建。**尚未实现 Node / fnOS 曲库、登录、队列与原生应用之间的协议适配或数据迁移。** 本工程原有本地曲库与手机话筒由应用自身提供。

## 构建与安装

需要 JDK 17+、Android SDK platform 35 和 build-tools 35.0.0。已统一使用 Android Gradle Plugin 8.6.0、Kotlin 1.9.24 和 Gradle 8.7。

```sh
bash native-tv/gradlew -p native-tv --no-daemon :app:assembleDebug :app:testDebugUnitTest
```

APK：`native-tv/app/build/outputs/apk/debug/app-debug.apk`。GitHub Actions 的 `MDKTV-native-TV-debug` artifact 也会提供 APK，单元测试结果单独保存。

独立应用 ID 为 `org.mdktv.nativeapp`，调试包带 `.qa` 后缀，可与其他版本并存。保留上游 Android 5.0+ 最低版本和 targetSdk 25 的兼容设定，以保持原有存储/权限流程；这不是 Google Play 上架配置。数据目录为 `/storage/emulated/0/MDKTV_Native`。集成时移除了上游对共享 `/storage/emulated/0/muse` 目录的递归清理，并将远程日志 API 改为仅写本机 KtvLog。正式签名需自行配置；移除了上游写死的密钥路径和口令，release 默认不签名。

安装后按应用流程授予必要权限、使用自己的合法媒体。手机与电视处于同一局域网，通过应用的话筒二维码进入。手机录音需要安全上下文；上游附带的局域网测试证书仍保留，浏览器的信任与录音权限需在实机确认。手机网络话筒存在网络和缓冲延迟，不代表硬件级低延迟返听。

## 验证和限制

本地 `assembleDebug` 已成功，31 项原有 JVM 单元测试全部通过。两个 ARM 架构的 ELF 架构、动态库依赖及最终 APK 内的完整 IJK 库集合已检查，调试签名验证通过。

VocalScorer 按人声覆盖、音高稳定性、能量评分，没有与歌曲标准旋律比对，因此不能宣称专业音准评分。MicActivator 使用 AudioRecord 激活输入并读取丢弃音频，依赖设备自身路由；没有实现独立的 USB 麦克风采集到播放返听链路。未新增 Oboe、C++ JNI 音效链或参考谱数据库。

IJK 使用上游的预编译 JAR/SO，不是从 C/C++ 源码重建。解码工程缺少两个架构的 `libijkffmpeg.so`；从 QiuYiLiang/maidong-ktv 补齐之前，已确认该分支的原 JAR 与现有五个 SO 全部逐字节一致。二进制依赖来源及摘要见 `IJK-RUNTIME-PROVENANCE.json`。运行兼容性仍需在 ARMv7/ARM64 实机验证；构建和 JVM 单测通过不等于电视播放、手机收音、HTTPS 信任或端到端评分已验收。

遵守本目录 LICENSE 的免费、禁止商用和署名要求；IJK、FFmpeg 等组件适用各自许可。本仓库根目录 THIRD-PARTY-COMPONENTS.md 亦保留相关历史组件说明，不将本工程整体改为 MIT/Apache-2.0。
