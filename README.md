# Server Core

## Setup

For setup instructions, please see the [Fabric Documentation page](https://docs.fabricmc.net/develop/getting-started/creating-a-project#setting-up) related to the IDE that you are using.

## License

This template is available under the CC0 license. Feel free to learn from it and incorporate it in your own projects.

## 网易云共享唱片机

本项目新增客户端/服务端多人共享唱片机（Minecraft 26.3 Fabric）。两端都安装 `build/libs/server-core-1.0.0.jar`。

- 每位玩家配置自己信任的 [NeteaseCloudMusicApi Enhanced](https://github.com/NeteaseCloudMusicApiEnhanced/api-enhanced) 服务，默认 `http://127.0.0.1:3000`；远程 API 必须 HTTPS。本模组不提供公共网关。
- 进入服务器后先完成原有 `/register` 或 `/login`，再对原版唱片机 Shift+右键打开界面。扫码登录网易云、搜索歌曲并点击结果即可共享播放。
- 服务端只同步唱片机坐标、歌曲 ID、标题、时长和进度；每位客户端用自己的网易云会话获取 MP3。Cookie 仅保存在内存，配置文件只保存 API 地址。
- 同维度 32 格内自动收听，离开范围、拆除方块、断线或歌曲结束自动停止；支持停止共享和重试收听。每台唱片机最多 128 个活动实例，操作需要距离 8 格内并通过服务器登录。
- 仅播放 `/song/url` 返回的 MP3，拒绝无权限、试听和非网易云 CDN 音源；不绕过版权或会员限制。声音通过 Java Sound 输出并按距离衰减。

构建：`gradlew.bat build`。构建会运行本地模拟 API 安全测试（9 项），不需要真实账号。JLayer 许可证见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
