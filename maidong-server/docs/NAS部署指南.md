# MDKTV Node 服务 NAS 部署

需要 NAS Docker/Compose；NAS、手机与电视在可信局域网。此服务与根目录 `bridge/` 的 fnOS Python 服务 API 不同，配置和账号不通用。

## 从当前源码构建

```sh
git clone --branch codex/import-maidong-tv-server https://github.com/k7767c/MDKTV.git
cd MDKTV/maidong-server
docker compose up -d --build
```

默认持久化到当前目录 `music/` 和 `data/`，HTTP 端口 8080，UDP 发现端口 18888。如果已有其他程序占用这些端口，修改 compose 映射；APK 自动扫描默认探测 8080，修改端口后使用手动连接。

电视打开 `http://NAS_IP:8080/tv`，手机打开 `http://NAS_IP:8080/m`。安装 `android-tv/` 构建出的 APK，可自动搜索服务或输入 `NAS_IP:8080`；菜单键返回连接设置。健康检查：`curl http://NAS_IP:8080/api/v1/health`。

需要 host 网络或 NAS 绝对路径卷时，修改 `docker-compose.nas.yml` 的目录后执行 `docker compose -f docker-compose.nas.yml up -d --build`。该配置也从当前源码构建，不拉取来源维护者的镜像。

## 媒体与运行环境

镜像包含 Node 22 和 ffmpeg。直接用 Node 运行需要 Node 22.13+（`node:sqlite`）以及 ffmpeg/ffprobe。使用自己的合法媒体和可信音源脚本；不内置曲库数据库、账户或歌曲。`muse.db` 缺失时相关榜单不可用，外部源接口与实际播放需自行验证。`ready` 表示脚本源状态，不是所有曲库/媒体的可用性承诺。

此上游服务没有账户隔离，不应直接开放到公网。备份数据卷和媒体卷；升级先拉取源码，再重新 `docker compose up -d --build`。查看日志：`docker compose logs -f`。

提取来源、许可情况与验证边界见根目录 `IMPORT-MAIDONG.md` 和 `UPSTREAM-MAIDONG.json`。原方案由 Mrtangji/maidong-ktv 提供；本文件已适配当前仓库布局。
