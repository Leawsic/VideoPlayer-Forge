# VideoPlayer-Forge

Forge 1.20.1 模组：在游戏世界中播放视频、直播流、B 站视频，支持多屏同步、弹幕、预设快捷创建。

**Mod ID**: `videoplayer`
**版本**: `1.6.4.1`
**依赖**: Forge 47.4.0, Java 17, vlcj 4.10.1 (内置 natives)
**服务端无需安装 VLC** —— 纯时钟驱动播放进度与队列。

---

## 命令速查表

| 命令 | 权限 | 说明 |
|------|------|------|
| `/vlc createAreaHere <name> [radius]` | OP | 以玩家位置为中心创建观影区 |
| `/vlc removeArea <name>` | OP | 删除观影区 |
| `/vlc createScreenHere <area> <name> [preset]` | OP | 在观影区创建屏幕，可选预设 |
| `/vlc removeScreen <area> <name>` | OP | 删除屏幕 |
| `/vlc play <area> <screen> <url>` | OP | 播放任意网络流 / 本地文件 / B 站链接 |
| `/vlc stop <area> <screen>` | OP | 停止并清空队列，释放解码器 |
| `/vlc pause <area> <screen>` | OP | 暂停（保留解码器、进度、队列） |
| `/vlc resume <area> <screen>` | OP | 恢复暂停的播放 |
| `/vlc seek <area> <screen> <millis>` | OP | 定位到指定毫秒 |
| `/vlc forward <area> <screen> <millis>` | OP | 快进指定毫秒 |
| `/vlc back <area> <screen> <millis>` | OP | 快退指定毫秒 |
| `/vlc skip <area> <screen> [force]` | OP/玩家 | 投票/强制跳过当前视频 |
| `/vlc skipPercent <area> <screen> <0-1>` | OP | 设置投票跳过比例 |
| `/vlc preset list` | 所有人 | 列出内置与自定义预设 |
| `/vlc preset apply <preset> <area> <screen>` | OP | 应用预设到屏幕 |
| `/vlc preset save <name> <width> <height>` | OP | 保存自定义预设 |
| `/vlc preset remove <name>` | OP | 删除自定义预设 |
| `/vlc danmaku true|false` | OP | 开关 B 站弹幕 |
| `/vlc danmaku opacity <0-100>` | OP | 设置弹幕不透明度 |
| `/vlc danmaku scale <20-400>` | OP | 设置弹幕字号缩放 |
| `/vlc danmaku speed <10-400>` | OP | 设置弹幕滚动速度 |
| `/vlc danmaku max <1-200>` | OP | 设置最大同屏弹幕数 |

---

## 预设

| 名称 | 宽x高 | 适用场景 |
|------|-------|----------|
| `small` | 4x2.25 | 小型电视 |
| `cinema-large` | 32x18 | 影院巨幕 |
| `wall` | 16x9 | 墙面投影 |
| `floating` | 8x4.5 | 悬浮屏 |

自定义预设保存在 `config/videoplayer-presets/*.json`，格式：
```json
{ "width": 12, "height": 6.75 }
```

---

## B 站弹幕

**支持范围**：
- 仅普通 BV 投稿（含分 P）
- 仅模式 1（滚动文本弹幕）
- 不支持直播、固定弹幕、高级定位、表情图片

**数据来源**：
- 视频信息：`https://api.bilibili.com/x/web-interface/view?bvid=<BV>`
- 弹幕分段：`https://api.bilibili.com/x/v2/dm/web/seg.so?type=1&oid=<CID>&segment_index=<N>`

**实现要点**：
- 客户端直接下载 protobuf `seg.so` 分段，不经服务端转发
- 当前 + 未来 6 分钟预取，滑动窗口自动清理旧段
- lane 由 (文本+时间戳) 哈希稳定生成，不随视角漂移
- 字号自适应视频高度（基准 12 条轨道），八向黑色描边保证对比度
- 配置持久化到 `videoplayer-client.json`

---

## 同步播放机制（核心逻辑）

### 服务端：纯时钟 `ClockListener`
- **无 VLC 依赖**：服务端不加载 libVLC，仅用 `System.currentTimeMillis()` 推算进度
- `VideoInfo.duration`（毫秒）决定自动推进：
  - `>0`：到点触发 `stopped` 回调 -> 2 秒后 `poll` 队列 -> `playNext()`
  - `<=0`（直播/未知时长）：不自动结束，等待手动 `skip`/`stop`
- `pause/resume/seek` 直接调整时钟基准并重排定时任务
- 进度、暂停状态随 `LOAD_AREA` 下发，新进玩家自动同步

### 客户端：VLC 解码 + 自适应同步
- `VlcDecoder` 封装 vlcj `EmbeddedMediaPlayer`，回调解码帧上传纹理
- `autoSync`（可在屏幕 meta 开启）：客户端每 ~150ms 上报本地进度，服务端按 RTT 修正后下发目标进度
- 客户端按差值动态调整播放速率（0.8x-3x），超过 +-10s 强制重定位
- 暂停时停止 autoSync，避免抖动

### 包协议（关键包）
| 包号 | 方向 | 用途 |
|------|------|------|
| `REQUEST` (1) | S->C | 请求客户端播放指定 `VideoInfo` |
| `STOP` (20) | S->C | 停止播放、清理解码器 |
| `PAUSE` (21) | S->C | 暂停 |
| `RESUME` (22) | S->C | 恢复 |
| `SEEK` (23) | S->C | 定位 |
| `LOAD_AREA` (7) | S->C | 玩家进入区域时全量同步：队列、进度、暂停状态 |
| `AUTO_SYNC` (19) | C->S->C | 双向同步进度（客户端上报 -> 服务端修正 -> 广播） |

---

## 架构要点

- **网络层**：Forge `SimpleChannel`，统一 `VideoPacket(byte[])`，按 `PacketID` 分发
- **Provider 体系**：`IVideoProvider` -> `BiliBiliVideoProvider` / `BiliBiliLiveProvider` / `NetworkProvider`，返回含 `duration/seekable` 的 `VideoInfo`
- **渲染管线**：`VideoQuad` (PBO 双缓冲) -> `VlcDecoder` 回调解码 -> `CallbackVideoSurface` -> GL 纹理
- **Mixin**：`WorldRendererMixin` 注入 `LevelRenderer.renderClouds`，360 度天空盒时屏蔽云层
- **配置**：`videoplayer-client.json` (弹幕/音量/调试) + `videoplayer-presets/` (预设)

---

## 常见问题

| 现象 | 原因 | 处理 |
|------|------|------|
| 服务端控制台报 `libvlc` 加载失败 | **正常** —— 服务端不需要 VLC，仅客户端需安装 | 忽略 |
| 任意网络流无法播放 | 服务端不再探测，`seekable=false, duration=-1` | 客户端各自由 VLC 判断能否播放 |
| 直播/未知时长视频不自动下一条 | `duration <= 0` 不启动定时器 | 手动 `/vlc skip` 或 `/vlc stop` 后再 `play` |
| 重进观影区画面卡住 | URL 可能已过期（B 站 CDN 有效期 ~10 min） | 重新 `/vlc play` 刷新链接 |

---

## 构建与 CI

```bash
./gradlew build          # 本地构建（产物在 build/libs/）
```

**权威构建**：GitHub Actions `.github/workflows/build.yml`
- JDK 17 (Temurin)
- `./gradlew build`
- 上传 `build/libs/*.jar` 为 artifact

---

## 致谢

- 原项目：[squi2rel/VideoPlayer-backport](https://github.com/squi2rel/VideoPlayer-backport) (Fabric 1.20.1)
- vlcj / JNA / libVLC
- Forge / MCP / Yarn mappings