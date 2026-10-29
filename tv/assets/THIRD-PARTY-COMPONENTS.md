# 飞牛 K歌使用的开源组件

本版保留现有 Android / Media3 播放外壳和飞牛认证、曲库桥接，不是 UltraStar Play 的 Android 移植版。该项目当前上游最低 API 25，本机电视为 API 23；未声称完成 Unity 兼容移植。

- **Glide 4.16.0**：Android 封面加载、缓存及圆形裁剪。BSD / Apache 组件许可原文随 APK 打包在 `assets/licenses/Glide-LICENSE`，关于页面可查看。来源：https://github.com/bumptech/glide/tree/v4.16.0
- **faster-whisper 1.2.1**（MIT）：NAS 上离线生成演唱字词时间，音频不发往网络。来源：https://github.com/SYSTRAN/faster-whisper/tree/v1.2.1
- **faster-whisper-small 模型**：Systran 发布的 Whisper small CTranslate2 模型，固定模型提交 `536b0662742c02347bc0e980a01041f333bce120`。模型保存在 NAS 私有目录，未纳入 Git。来源：https://huggingface.co/Systran/faster-whisper-small
- **Demucs / CREPE**：继续使用已有分离人声及测量音高流程；字词区间只是有效演唱门限，不生成虚假音符。
- **FFmpeg / ffprobe**：检查真实视频流（排除附带封面），提取视频缩略图，解码评分音源。复用 NAS 已安装的工具，未重新分发其二进制。
- **UltraSinger**：参考其“转录演唱区间约束音高”的整体方法，未拷贝其程序源码，也未将其宣称为已集成的整套引擎。来源：https://github.com/rakuri255/UltraSinger

原项目许可、原作者署名及历史文件保留。产品 UI 使用新名称不变更原许可。自动时间识别仍是实验参考：现场对白、和声、低置信度识别、无时间歌词可存在误差；不等同于人工逐字曲谱或全民 K 歌评分标准。

- **KuGouMusicApi**（MIT）：可选在线音源适配器。安装器固定下载 [MakcRe/KuGouMusicApi](https://github.com/MakcRe/KuGouMusicApi/tree/b34a8b14de0f3de73067b5141456e254176472e0) 的 `b34a8b14de0f3de73067b5141456e254176472e0`，保留其许可；npm 依赖按 `deploy/kugou-package-lock.json` 锁定。该项目是第三方 API 封装，源码许可不授予音乐版权或会员权益。使用本人登录与上游实际返回的可用资源。
- **Node.js v22.23.2**：可选音源服务运行时，安装器从 nodejs.org 下载并校验 SHA-256；仅安装到应用私有目录。

fnOS 独立演唱客户端还随包使用以下组件，完整许可存放于 `app/server/web-assets/vendor/`：

- **hls.js 1.7.3**（Apache-2.0）：同源 HLS 解码和 MV 多音轨选择，代码来自官方 npm 包，来源及摘要见 `bridge/web-assets/vendor/HLS-PROVENANCE.json`。
- **qrcode-generator 2.0.4**（MIT）：在当前设备绘制官方夸克登录二维码，不向第三方图片服务传送授权地址；来源及摘要见 `bridge/web-assets/vendor/QRCODE-PROVENANCE.json`。
- **Signalsmith Stretch / Signalsmith Linear**（MIT）、**STK**（原 STK 许可）、**DSPFilters**（MIT）：复用原 Android 的音效源码，编译为浏览器 WASM。原许可分别以 `Signalsmith-Stretch.LICENSE.txt`、`Signalsmith-Linear.LICENSE.txt`、`STK.LICENSE.txt`、`DSPFilters.LICENSE.txt` 保留。浏览器适配代码和可重建脚本位于 `tools/web_dsp*` 与 `tools/build_web_dsp.py`。
