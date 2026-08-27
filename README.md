# VideoPlayer-Forge

Forge 1.20.1 模组：在游戏世界中播放视频、直播流、B 站视频，支持多屏同步、弹幕、预设快捷创建。

**Mod ID**: `videoplayer`
**版本**: `1.6.4.1`
**依赖**: Forge 47.4.0, Java 17, vlcj 4.10.1 (内置 natives)
**服务端无需安装 VLC** —— 纯时钟驱动播放进度与队列。

---

## 权限模型

本模组**不检查 OP 权限**。所有命令的可用性由客户端根据连接状态与玩家位置/视角自动判断：

| 等级 | 条件 | 典型命令 |
|------|------|----------|
| **无限制** | 仅需安装模组 | `preset list` |
| **仅需连接** | 已连接服务器 (`connected=true`) | `volume`、`createAreaHere`、`createArea`、`removeArea`、`createScreenHere`、`createScreen`、`preset apply/save/remove` |
| **需在观影区且有主屏幕** | 处于观影区内，且该区存在主屏幕 (`currentScreen != null`) | `play`、`skip`、`stop`、`pause`、`resume`、`seek`、`forward`/`back`、`sync`、`idleplay`、`list`、`skipPercent`、`removeScreen`、`danmaku *`、`setmeta *` |
| **需注视屏幕** | 准星指向某屏幕 (`currentLooking != null`) | `brightness`、`slice` |

> 服务端 `RegisterCommandsEvent` 仅将完整命令字符串转发给客户端执行，服务端侧 `// TODO check permission` 尚未实现。

---

## 命令完整列表

### 区域与屏幕管理
| 命令 | 参数 | 权限等级 | 说明 |
|------|------|----------|------|
| `/vlc createAreaHere` | `<name> [radius]` | 仅需连接 | 以玩家为中心创建立方体观影区，默认半径 16 |
| `/vlc createArea` | `<x1> <y1> <z1> <x2> <y2> <z2> <name>` | 仅需连接 | 指定两个角坐标创建观影区 |
| `/vlc removeArea` | `<name>` | 仅需连接 | 删除观影区及其所有屏幕 |
| `/vlc createScreenHere` | `<area> <name> [preset]` | 仅需连接 | 在观影区按预设创建屏幕，面向玩家或所视方块 |
| `/vlc createScreen` | `<area> <name> <x1> <y1> <z1> <x2> <y2> <z2> <x3> <y3> <z3> <x4> <y4> <z4> <source>` | 仅需连接 | 指定 4 个顶点坐标精确创建屏幕 |
| `/vlc removeScreen` | `<area> <name>` | 需主屏幕 | 删除指定屏幕 |

### 播放控制
| 命令 | 参数 | 权限等级 | 说明 |
|------|------|----------|------|
| `/vlc play` | `<url>` | 需主屏幕 | 在当前主屏幕播放（支持 B 站链接/直播/网络流/本地文件）。注意播放链接需[处理](#链接处理)！ |
| `/vlc playthat` | `<area> <screen> <url>` | 需主屏幕 | 在指定屏幕播放 |
| `/vlc stop` | — | 需主屏幕 | 停止并清空队列，**释放解码器/纹理** |
| `/vlc pause` | — | 需主屏幕 | 暂停，**保留解码器/进度/队列**，画面定格 |
| `/vlc resume` | — | 需主屏幕 | 恢复暂停的会话 |
| `/vlc seek` | `<millis>` | 需主屏幕 | 定位到指定毫秒 |
| `/vlc forward` | `<millis>` | 需主屏幕 | 快进指定毫秒 |
| `/vlc back` | `<millis>` | 需主屏幕 | 快退指定毫秒 |
| `/vlc skip` | `[force]` / `<area> <screen> [force]` | 需主屏幕 | 投票跳过 / 强制跳过当前视频 |
| `/vlc skipPercent` | `<0-1>` | 需主屏幕 | 设置投票跳过通过比例 |
| `/vlc sync` | — | 需主屏幕 | 手动触发一次进度同步 |
| `/vlc idleplay` | `<url>` | 需主屏幕 | 空闲播放（队列为空时自动播放） |
| `/vlc list` | — | 需主屏幕 | 显示当前屏幕播放队列 |

#### 链接处理

从B站上拿到的链接需进行处理，将播放数据trace tag去除。操作如下：

原链接：https://www.bilibili.com/video/BV1db8x6VE73/?spm_id_from=333.337.search-card.all.click

https://live.bilibili.com/26843450?hotRank=0&session_id=5250dc6dd71fda2e26828fb9996a9014_0E1B29B0-CA97-42B1-9E78-79CF779618F7&live_from=77002&trackid=live_feed_0.router-live-2482124-drxhl.1787827286351.574&visit_id=6047xe9a60w0

处理：https://www.bilibili.com/video/BV1db8x6VE73/

https://live.bilibili.com/26843450

基本上就是把第一个问好后面的内容全部删掉

### 预设系统

| 命令 | 参数 | 权限等级 | 说明 |
|------|------|----------|------|
| `/vlc preset list` | — | 无限制 | 列出内置与自定义预设 |
| `/vlc preset apply` | `<preset> <area> <screen>` | 仅需连接 | 将预设尺寸/UV/缩放应用到屏幕 |
| `/vlc preset save` | `<name> <width> <height>` | 仅需连接 | 保存自定义预设到 `config/videoplayer-presets/` |
| `/vlc preset remove` | `<name>` | 仅需连接 | 删除自定义预设 |

**内置预设**：

| 名称 | 宽×高 | 适用场景 |
|------|-------|----------|
| `small` | 4×2.25 | 小型电视 |
| `cinema-large` | 32×18 | 影院巨幕 |
| `wall` | 16×9 | 墙面投影 |
| `floating` | 8×4.5 | 悬浮屏 |

自定义预设格式（JSON）：
```json
{ "width": 12, "height": 6.75, "u1": 0, "v1": 0, "u2": 1, "v2": 1, "fill": false, "scaleX": 1, "scaleY": 1, "meta": {} }
```

### 画面调整
| 命令 | 参数 | 权限等级 | 说明 |
|------|------|----------|------|
| `/vlc brightness` | `<0-100>` | 需注视屏幕 | 客户端亮度（不下发服务端） |
| `/vlc slice` | `<u1> <v1> <u2> <v2>` | 需注视屏幕 | UV 裁剪区域（0-1） |
| `/vlc setmeta` | `<area> <screen> mute <bool>` | 需主屏幕 | 静音切换 |
| `/vlc setmeta` | `<area> <screen> interactable <bool>` | 需主屏幕 | 鼠标穿透切换 |
| `/vlc setmeta` | `<area> <screen> aspect <0.0625-16>` | 需主屏幕 | 强制宽高比 |
| `/vlc setmeta` | `<area> <screen> fov <1-179>` | 需主屏幕 | 360° 视场角 |
| `/vlc setmeta` | `<area> <screen> autoSync <bool>` | 需主屏幕 | 开启/关闭自动同步 |
| `/vlc setmeta` | `<area> <screen> custom set <key> <value>` | 需主屏幕 | 设置自定义元数据 |
| `/vlc setmeta` | `<area> <screen> custom get <key>` | 需主屏幕 | 读取自定义元数据 |
| `/vlc setmeta` | `<area> <screen> custom remove <key>` | 需主屏幕 | 删除自定义元数据 |
| `/vlc setmeta` | `<area> <screen> custom list` | 需主屏幕 | 列出所有自定义元数据 |

### B 站弹幕
| 命令 | 参数 | 权限等级 | 说明 |
|------|------|----------|------|
| `/vlc danmaku` | `true/false` | 需主屏幕 | 开关弹幕叠加 |
| `/vlc danmaku opacity` | `<0-100>` | 需主屏幕 | 不透明度 |
| `/vlc danmaku scale` | `<20-400>` | 需主屏幕 | 字号缩放百分比 |
| `/vlc danmaku speed` | `<10-400>` | 需主屏幕 | 滚动速度百分比 |
| `/vlc danmaku max` | `<1-200>` | 需主屏幕 | 最大同屏条数 |

> 弹幕配置持久化到 `videoplayer-client.json`，仅客户端生效。

---

## B 站弹幕实现细节

**支持范围**：
- 仅普通 BV 投稿（含分 P），通过 `x/web-interface/view` 获取 CID 与时长
- 仅模式 1（滚动文本弹幕），通过 `x/v2/dm/web/seg.so` 分段拉取 protobuf
- **不支持**：直播弹幕、固定/高级弹幕、表情图片、特殊颜色

**客户端流程**：
1. 解析 BV/分 P → 查询 CID
2. 当前时间 + 未来 6 分钟按 6 分钟分段预取 `seg.so`
3. 最小 protobuf 解析 → 按 (文本+时间戳) 哈希分配固定 lane，不随视角漂移
4. 字号 = 视频高度 / 12（基准 12 条轨道），`Font.drawInBatch8xOutline` 八向黑色描边
5. 滑动窗口自动清理过期分段，配置实时生效

---

## 同步播放机制（核心逻辑）

### 服务端：纯时钟 `ClockListener`
- **无 VLC 依赖**：服务端不加载 libVLC，仅用 `System.currentTimeMillis()` 推算进度
- `VideoInfo.duration`（毫秒）决定自动推进：
  - `>0`：到点触发 `stopped` 回调 → 2 秒后 `poll` 队列 → `playNext()`
  - `≤0`（直播/未知时长）：不自动结束，等待手动 `skip`/`stop`
- `pause/resume/seek` 直接调整时钟基准并重排定时任务
- 进度、暂停状态随 `LOAD_AREA` 下发，新进玩家自动同步

### 客户端：VLC 解码 + 自适应同步
- `VlcDecoder` 封装 vlcj `EmbeddedMediaPlayer`，回调解码帧上传纹理
- `autoSync`（屏幕 meta `autoSync=1` 开启）：客户端每 ~150ms 上报本地进度，服务端按 RTT 修正后下发目标进度
- 客户端按差值动态调整播放速率（0.8×–3×），超过 ±10s 强制重定位
- 暂停时停止 autoSync，避免抖动
- 完播后 `finished` 标志冻结进度条，不再走秒

### 关键包协议
| 包号 | 方向 | 用途 |
|------|------|------|
| `REQUEST` (1) | S→C | 请求客户端播放指定 `VideoInfo` |
| `STOP` (20) | S→C | 停止播放、清理解码器 |
| `PAUSE` (21) | S→C | 暂停 |
| `RESUME` (22) | S→C | 恢复 |
| `SEEK` (23) | S→C | 定位 |
| `LOAD_AREA` (7) | S→C | 玩家进入区域全量同步：队列、进度、暂停状态 |
| `AUTO_SYNC` (19) | C→S→C | 双向同步进度（客户端上报 → 服务端修正 → 广播） |

---

## 架构要点

- **网络层**：Forge `SimpleChannel`，统一 `VideoPacket(byte[])`，按 `PacketID` 分发
- **Provider 体系**：`IVideoProvider` → `BiliBiliVideoProvider` / `BiliBiliLiveProvider` / `NetworkProvider`，返回含 `duration/seekable` 的 `VideoInfo`
- **渲染管线**：`VideoQuad` (PBO 双缓冲) → `VlcDecoder` 回调解码 → `CallbackVideoSurface` → GL 纹理
- **Mixin**：`WorldRendererMixin` 注入 `LevelRenderer.renderClouds`，360° 天空盒时屏蔽云层
- **配置**：`videoplayer-client.json` (弹幕/音量/亮度/调试) + `videoplayer-presets/` (预设)

---

## 常见问题

| 现象 | 原因 | 处理 |
|------|------|------|
| 服务端控制台报 `libvlc` 加载失败 | **正常** —— 服务端不需要 VLC，仅客户端需安装 | 忽略 |
| 任意网络流无法播放 | 服务端不再探测，`seekable=false, duration=-1` | 客户端各自由 VLC 判断能否播放 |
| 直播/未知时长视频不自动下一条 | `duration ≤ 0` 不启动定时器 | 手动 `/vlc skip` 或 `/vlc stop` 后再 `play` |
| 重进观影区画面卡住 | URL 可能已过期（B 站 CDN 有效期 ~10 min） | 重新 `/vlc play` 刷新链接 |
| 暂停/定位不生效 | 必须在观影区内且面对主屏幕（`currentScreen != null`） | 走进观影区、面对屏幕后再执行命令 |

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