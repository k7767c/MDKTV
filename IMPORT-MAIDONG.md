# Mrtangji/maidong-ktv 提取记录

来源：<https://github.com/Mrtangji/maidong-ktv>，提交 `3c7b4c8537494d235616ebd07e642c3804b6514e`。
完整路径与原始 SHA-256 见 `UPSTREAM-MAIDONG.json`。此清单记录提取时的原始内容，集成修改由本仓库 Git 差异追溯。

## 已补齐

| 内容 | 路径 | 与现有版本的关系 |
| --- | --- | --- |
| Android TV Kotlin/Gradle 工程、局域网探测、连接验证、全屏播放、图标 | `android-tv/` | 连接下方 Node 服务，自动打开 `/tv` |
| TV 大屏、遥控器交互、HLS 及图形资源 | `maidong-server/web/tv/` | 配套 Node API，不能直接接 fnOS Python API |
| 手机点歌入口 | `maidong-server/web/m/` | 与 TV 共享 Node 服务队列 |
| HTTP API、UDP 发现、共享队列及落盘 | `maidong-server/server.js`、`src/queue.js` | 与 `bridge/` 服务独立运行 |
| 搜索、歌词、榜单、音源脚本、缓存、批量下载、TS/HLS 处理 | `maidong-server/src/` | 外部来源可用性与授权需自行确认 |
| Docker 构建及 NAS 部署配置 | `maidong-server/Dockerfile`、compose 文件、`docs/` | 不修改现有 fnOS 包 |
| 本地 TS 检查、服务端联通与重启持久化验证 | `maidong-server/test/` | 修复原测试的 Windows 绝对路径 |
| 两个 TV 工程的 APK 自动构建 | `.github/workflows/tv-build.yml` | GitHub Actions 中下载调试 APK artifact |

源仓库 Docker 发布流程保存在 `maidong-server/upstream/docker-publish.yml` 作为参考，不激活其镜像发布。新增 APK 工作流不需要 Docker Hub 密钥，也不发布镜像。

集成时还为 Kotlin 客户端加入菜单键返回连接设置、WebView 销毁状态保护和导航协议限制；关闭不需要的本地文件访问。NAS compose 改为构建本仓库源码，不默认拉取其他维护者的镜像。TV 自带 hls.js 1.5.13 已与 npm 官方发行字节核对一致，补充 LICENSE 与摘要文件。

## 使用

从本仓库源码构建服务端，不依赖原维护者的镜像：

```sh
cd maidong-server
docker compose up -d --build
```

电视：`http://NAS_IP:8080/tv`，手机：`http://NAS_IP:8080/m`。
Android 工程构建：JDK 17、Android SDK 35、Gradle 8.7，执行 `gradle -p android-tv :app:assembleDebug`。
输出 `android-tv/app/build/outputs/apk/debug/app-debug.apk`。它使用 `com.maidong.ktv.tv` 包名，与初版 `org.mdktv.tv` 可并存。

原 `tv/` 初版保留，用于手动输入完整网页地址连接 fnOS 或 Node 服务。现有 `bridge/` 的配置、授权目录和数据库不会被自动迁移到 Node 服务。

Node 服务要求 Node 22.13+（使用内置 `node:sqlite`），MV 转封装需要 ffmpeg/ffprobe。缺少 `muse.db`、音源或合法媒体时，对应曲库/播放功能不可用；此次不提取账户、凭据、数据库或媒体。该服务原版无账户隔离，只用于可信局域网；音源脚本仅导入自己信任的代码。

## 来源与边界

来源仓库此提交没有 LICENSE 文件，不能将根目录原项目许可证或第三方许可自动视为其全部新增代码与图片的授权。保留来源注释和原 README，不新增 MIT/Apache 整体授权声明。现有原作者署名、LICENSE 和 THIRD-PARTY-COMPONENTS.md 均保留。对外分发前需确认来源新增内容的授权。

没有从来源中找到原生麦克风采集、低延迟返听、原生评分引擎或 C++ JNI 播放工程，不能据此宣称已补齐这些能力。源码提取与本地验证也不等于外部曲库、真实 NAS、电视遥控器或音视频播放已验收。
