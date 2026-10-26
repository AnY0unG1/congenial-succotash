# Server Core

当前版本：**1.1.0**。完整更新记录见 [CHANGELOG.md](CHANGELOG.md)，发布 JAR 内也包含该文件。

## Setup

For setup instructions, please see the [Fabric Documentation page](https://docs.fabricmc.net/develop/getting-started/creating-a-project#setting-up) related to the IDE that you are using.

## License

This template is available under the CC0 license. Feel free to learn from it and incorporate it in your own projects.

## 网易云共享唱片机

本项目新增客户端/服务端多人共享唱片机（Minecraft 26.3 Fabric）。两端都安装 `build/libs/server-core-1.1.0.jar`。

- 搜索、歌曲详情、账号歌单和登录播放地址均由服务器直连网易云，不需要额外 API 网关。全服共用服务器配置文件 `config/server-core-music.json` 中的 `cookie`；Cookie 不下发到客户端。
- 进入服务器后先完成原有 `/register` 或 `/login`，再对原版唱片机 Shift+右键打开界面。搜索歌曲并点击结果即可共享播放，也可粘贴网易云歌曲链接后点击“链接播放”，自动读取歌名、歌手和时长。支持 `song?id=`、`#/song?id=`、`/m/song?id=` 和网易云歌曲 MP3 外链。
- 搜索结果每页 5 首，支持上一页、下一页。点击“账号歌单”读取共享账号创建和收藏的歌单，点击歌单查看歌曲；歌单和歌曲均支持翻页，再次点击“账号歌单”返回歌单列表。
- 播放控制包含上一首、暂停/继续、下一首，以及可点击切换的顺序播放、随机播放、单曲循环、列表循环。播放队列和进度由服务器保存，关闭界面后仍会自动切歌；暂停及切歌同步给附近玩家。暂停后继续会重新获取音源并定位到暂停位置。
- 从歌单选择歌曲时加载完整歌单（最多 10000 首），不受界面分页限制；从搜索结果选择时使用当前页歌曲作为队列，粘贴链接则使用单首队列。顺序播放到列表末尾停止；随机模式避免连续选中同一首，并支持返回播放历史；单曲循环不影响手动上一首/下一首。切歌失败会暂停，点击下一首可跳过，点击继续会重试该曲。
- 服务端同步唱片机坐标、歌曲 ID、标题、时长和进度，并为客户端解析该共享账号可用的播放地址；客户端从网易云 CDN 下载和播放音频。
- 同维度 32 格内自动收听，离开范围、拆除方块或断线自动停止收听；支持停止共享和重试收听。全服最多 128 台活动唱片机，操作需要距离 8 格内并通过服务器登录。
- 搜索、链接和歌单均不过滤 VIP 歌曲。配置 Cookie 后参考 BetterLogin 的 `/api/song/enhance/player/url/v1` 获取账号授权音源，使用 standard / MP3 以兼容现有播放器；账号无权限或只支持试听时显示错误。未配置 Cookie 时仍可搜索和使用公开外链。音源重定向逐次校验网易云域名，最多 5 次。

### 管理员登录与配置

以下命令需要 Minecraft 管理员权限（`COMMANDS_ADMIN`，对应 OP 3 级及以上）；专用服务器中玩家还需先完成 `/login`。

| 命令 | 功能 |
| --- | --- |
| `/music login` | 在执行命令的管理员客户端打开二维码，使用网易云音乐 APP 扫码确认；成功后自动保存服务器 Cookie，全服新请求立即使用此账号 |
| `/music login cancel` | 取消正在进行的扫码登录 |
| `/music cookie set MUSIC_U=你的值; __csrf=你的值` | 直接设置并保存完整 Cookie，不加外层引号 |
| `/music cookie reload` | 从服务器配置文件重载 Cookie |
| `/music cookie clear` | 清除并保存 Cookie，恢复公开外链播放 |
| `/music cookie status` | 查看是否已配置，不显示 Cookie 内容；实际登录有效性可通过“账号歌单”验证 |

二维码有效等待时间为 3 分钟；关闭二维码窗口、断线、管理员权限失效或手动修改 Cookie 都会取消本次登录。同一时间仅允许一个管理员发起共享账号扫码，新请求替换旧请求。扫码期间旧账号继续可用，只有确认并保存成功才切换。

首次启动服务器会生成配置；也可以编辑后执行重载，适用于超过游戏命令输入长度的 Cookie：

```json
{
  "cookie": "MUSIC_U=你的值; __csrf=你的值; os=pc; appver=3.1.6"
}
```

Cookie 更新后，新搜索、歌单和音源请求立即生效；已下载的歌曲可点击“重试收听”重新获取。二维码和账号请求在服务器后台执行，客户端仅接收二维码、状态、歌曲及歌单元数据和播放地址。

### 界面背景

唱片机面板基准为 460×288 GUI 单位，背景文件为 `src/main/resources/assets/server-core/textures/gui/music_background.png`，分辨率 920×576。当前背景来自用户提供的图片，等比缩放并居中裁剪；小窗口会根据可用空间等比裁剪显示。背景上叠加轻微暗色遮罩，使文字可读。

替换同路径 PNG 后重新构建即可更换背景；也可通过资源包覆盖 `assets/server-core/textures/gui/music_background.png`。建议使用 920×576 或 1840×1152 图片，并避免把重要内容放在最外侧边缘。

播放同步协议已更新，服务器与客户端必须使用同一新版模组。

构建：`gradlew.bat build`。构建会运行本地模拟 API 测试，不需要真实账号。音频与二维码依赖说明见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
