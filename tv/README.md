# MDKTV TV 0.1.0

Android 6.0+ 初始电视客户端，采用系统 WebView 连接本仓库的 K歌 / fnOS NAS 服务。不是独立曲库或内置 NAS 服务，不集成 Android 原生麦克风、评分、返听或 C++ 音效。

安装 `MDKTV-TV-0.1.0-debug.apk` 后，输入电视可以访问的服务完整地址，例如 `http://192.168.1.20:端口/`。fnOS 反向代理地址需要保留实际应用路径。首次连接需按服务端流程登录、连接自己的媒体来源。地址保存在本机；应用不内置账号或媒体。

遥控器方向键移动焦点、确认选择；返回键回退网页或返回连接页；菜单键打开连接页。页面提供基础方向导航增强，动态页面和不同电视 WebView 的焦点、解码能力需实机验证。若旧 WebView 不支持页面功能，需升级系统 WebView。

允许局域网 HTTP；公网连接应使用有效 HTTPS。不会忽略证书错误。APK 使用本地生成的开发签名，适用于初步安装验收，未上架商店。尚未完成实机遥控器、NAS 登录和音视频播放验收。

## 构建

安装 JDK 17+，Android SDK platform 35 和 build-tools 35.0.0，然后：

```sh
ANDROID_SDK_ROOT=/path/to/android-sdk JAVA_HOME=/path/to/jdk bash tv/build.sh
```

输出位于 `artifacts/tv/`。开发密钥仅保存在该忽略目录中；保留它才能使后续调试 APK 覆盖安装。不要将其用于正式发布。

基于麦动（Maidong KTV），原作者：杨家三郎；K歌由 FXD 维护。遵守根目录 LICENSE：完全免费、公开源码、禁止商用，并保留作者和项目署名。完整许可及第三方说明随 APK assets 和源码包提供。
