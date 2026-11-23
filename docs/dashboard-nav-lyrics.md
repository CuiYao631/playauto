# 副屏（仪表投射）导航与歌词数据 · 开发指南

> 本文基于真机逆向与实抓验证整理：
> - 盒子：`A15W`，固件 `2026.07.31.1733`，CarPlay 链路
> - 被测应用：AutoKit `2026.04.24.1419`（360 加固，已运行时脱壳）
> - 手机：MI 9 / Android 10，虚拟副屏 `1280x480 @160dpi`
>
> 与 `box-protocol.md` 互补：那份讲整体协议，本文专讲 **cmd 42 的媒体/导航数据**。

---

## 1. 数据从哪来

副屏要显示的两类内容都由 **`CMD_DASHBOARD_DATA (42)`** 下发，首 4 字节为**子类型**：

| 子类型 | 内容 | AutoKit 内部事件码 |
|---|---|---|
| `1` | 媒体信息（歌名、歌手、**歌词**、进度） | 216 → 60 |
| `2` / `3` | 专辑封面图（二进制） | 218 → 62 |
| `100` | 来电信息 | 217 → 61 |
| `200` | **导航 JSON** | 219 → 63 |
| `202` / `203` | 原始数据（未使用） | 未处理 |

另有两条独立的状态通知走 **`CMD_CARPLAY_CMD (8)`**：

| 子命令 | 含义 |
|---|---|
| `506` | 盒子通知：**导航开始** |
| `507` | 盒子通知：**导航结束** |

> **重要**：歌词走 CarPlay 标准的 NowPlaying（几乎所有盒子都支持）；
> 导航（200）需要盒子固件真正实现 CarPlay 导航采集，**且必须处于真实路线引导中**。

---

## 2. 前置条件（不满足就收不到数据）

### 2.1 必须告诉盒子副屏尺寸

盒子在拿到副屏尺寸前**不会进入仪表模式**。用 `CMD_BOX_SETTINGS (25)` 下发：

```json
{"DashboardInfo": 7}
```

```json
{"naviScreenInfo": {
  "fps": 30,
  "dpi": 160,
  "width": 1280,
  "height": 480,
  "safearea": {"width": 1280, "height": 480, "x": 0, "y": 0, "outside": 1}
}}
```

- `DashboardInfo` 固定 `7`
- `safearea` 是允许盒子绘制的区域；无自定义区域时 = 整屏
- 尺寸变化后要**重新下发** `naviScreenInfo`（playAuto 已有 `pendingNaviScreenInfo` 缓存 + 补发机制）

### 2.2 导航必须在 CarPlay 前台处于路线引导中

实测结论（很重要，容易误判成"盒子不支持"）：

| 状态 | `42` 子类型 | 结果 |
|---|---|---|
| 只打开地图，没进路线引导 | `{"1": 52, "3": 1}` | **收不到 200** |
| 真正开始路线引导 | `{"1": 55, "200": 21}` | ✅ 正常 |

所以：
- 调试前先确认车机屏幕上有**蓝色路线 + 转向提示 + 剩余距离**
- 若车机屏幕在音乐 App 前台，盒子只能读到媒体，读不到导航
- 在**手机本地**开导航、CarPlay 只镜像画面时，盒子拿不到导航指令（协议限制）

### 2.3 不要用 `supportFeatures` 判断导航能力

实测该盒子的 `supportFeatures` 上报为**空串**，但导航功能完全可用。
`naviScreen` 字段不可靠，**以能否收到 `42/200` 为准**。

---

## 3. 帧格式（复用 `box-protocol.md` 第 2 节）

```text
偏移  长度   字段
0     4     magic   0x55AA55AA（普通帧）/ 0x55BB55BB（加密控制帧）
4     4     length  负载长度（不含 16 字节头）
8     4     cmd     命令号
12    4     check   ~cmd
16    N     payload
```

`cmd 42` 的 `payload` 结构：

```text
[0..3]   int32  子类型（小端）
[4..N]   数据
```

⚠️ **子类型 1 / 100 / 200 的字符串末尾带一个 `\0`**，解析时要减 1：

```java
int end = payload.length;
while (end > 4 && payload[end - 1] == 0) end--;   // 或直接 end - 1
String json = new String(payload, 4, end - 4, StandardCharsets.UTF_8);
```

---

## 4. 歌词 / 媒体（subtype = 1）

### 4.1 字段

| 字段 | 类型 | 说明 |
|---|---|---|
| `MediaSongName` | String | 歌名 |
| `MediaArtistName` | String | 歌手 |
| `MediaAlbumName` | String | 专辑 |
| `MediaLyrics` | String | **歌词（单行）** |
| `MediaAPPName` | String | 媒体 App 名 |
| `MediaAppPkgName` | String | 媒体 App 包名 |
| `MediaSongDuration` | int | 总时长（ms） |
| `MediaSongPlayTime` | int | 当前进度（ms） |
| `MediaPlayStatus` | int | 播放状态 |

### 4.2 实抓样本

```json
{"MediaLyrics":"客串也别太潦草"}
{"MediaLyrics":"I wish you love me like you love my money baby","MediaArtistName":"Like I Love You · МОТ, Amirchik"}
{"MediaSongName":"作曲：Amirchik | Alex Davia | MOT","MediaAlbumName":"","MediaArtistName":"Like I Love You"} 
{"MediaSongPlayTime":210534}
{"MediaLyrics":"","MediaArtistName":"..."}   ← 歌词清空也用空串下发
```

### 4.3 关键特性：**增量包**

**每次只带发生变化的 1~6 个字段**，不是全量快照。

- 播放中每约 0.5s 来一条 `{"MediaSongPlayTime":xxxx}`
- 歌词变了才发 `MediaLyrics`
- **接收侧必须做字段缓存合并**，否则会被进度包清空歌词

AutoKit 内部缓存在 `d1.l` 的 `d`（道路）和 `e`（剩余距离）等字段里；
接收侧 `HUD.o()` 的入参出现 `road=null` 是**正常的**，内容在缓存里。

---

## 5. 导航（subtype = 200）

### 5.1 字段

| 字段 | 类型 | 说明 |
|---|---|---|
| `NaviStatus` | int | **0 = 导航结束；>0 = 导航中**（实测 `2`） |
| `NaviRoadName` | String | 当前道路 / 下一条路名 |
| `NaviRemainDistance` | int | **距下一转向的距离（米）** |
| `NaviTurnAngle` | int | 转向角度 |
| `NaviTurnSide` | int | 转向侧（左/右） |
| `NaviManeuverType` | int | 机动类型 |
| `NaviOrderType` | int | 旧版转向类型 |
| `NaviOrderTypeNew` | int | **新版转向类型（用于选图标）** |
| `NaviNextTurnTimeSeconds` | int | 到下一转向的秒数 |
| `NaviTimeToDestination` | int | 到目的地的秒数 |
| `NaviDistanceToDestination` | int | 到目的地的距离（米） |
| `NaviDestinationName` | String | 目的地名 |

### 5.2 实抓样本

```json
{"NaviRemainDistance":53}
{"NaviTimeToDestination":1320}
{"NaviRoadName":"进入 中贸街","NaviOrderType":3,"NaviTurnAngle":45,"NaviTurnSide":1,"NaviManeuverType":2,"NaviOrderTypeNew":3}
{"NaviTimeToDestination":1320,"NaviDistanceToDestination":8600}
```

### 5.3 增量包（同媒体）

导航也是**分字段下发**，必须合并：

```
{"NaviRemainDistance":53}      ← 只有距离
{"NaviRoadName":"进入 中贸街"}  ← 只有路名
```

AutoKit 的合并方式（`d1/l.java` `o(status, road, remain, t, d)`）：

```java
if (!TextUtils.isEmpty(str)) this.f2284d = str;   // road 为空则保留旧值
if (i2 != -1)                this.f2285e = i2;    // remain 为 -1 则保留旧值
```

**用 `-1` / `null` / 空串表示"本次未更新"**，不要覆盖。

---

## 6. NaviStatus 语义与防抖（重要坑）

### 6.1 语义

| 值 | 含义 | 动作 |
|---|---|---|
| `0` | 导航结束 | 清空文字（副屏回到纯黑） |
| `> 0`（实测 `2`） | 导航中 | 显示导航信息 |
| `-1`（缺省） | 本次未更新 | **保持现状，不做任何动作** |

AutoKit 原始处理（`d1/l.java`）：

```java
if (i == 0) {
    l.setNextRoad("");                 // 清空路名
    l.setEntireProgress("");           // 清空进度
    l.setNavTurnIconResource(-1);      // 清空转向图标
}
```

### 6.2 必须做防抖

**实测发现的坑**：导航刚建立时会**交替下发 `NaviStatus:2` 和 `NaviStatus:0`**，
直接按 0 清空会导致画面闪烁、看起来"根本没显示"。

推荐策略：

```java
private static final long CLEAR_DEBOUNCE_MS = 3000;
private long lastActiveAt;

void onNaviStatus(int status) {
    long now = SystemClock.elapsedRealtime();
    if (status > 0) {
        lastActiveAt = now;
        showNavigation();
    } else if (status == 0) {
        // 距离上次有效数据超过阈值才真的清空
        if (now - lastActiveAt > CLEAR_DEBOUNCE_MS) clearNavigation();
    }
    // status == -1：不做任何事
}
```

再加一层：**收到 `42/200` 或 `cmd 8 / 506` 都算"活跃"**，刷新 `lastActiveAt`。

---

## 7. 显示格式化规则

### 7.1 距离（对应 AutoKit `util/n0.b`）

```java
static String distance(int m) {
    if (m < 1000) return m + "m";
    double km = m / 1000.0;
    int i = (int) km;
    return (km == i) ? i + "km" : String.format("%.1fkm", km);
}
```

| 输入 | 输出 |
|---|---|
| `53` | `53m` |
| `350` | `350m` |
| `5600` | `5.6km` |
| `8000` | `8km` |

### 7.2 时间（对应 AutoKit `util/n0.c`）

```java
static String duration(int sec) {
    if (sec < 60) return sec + "秒";
    if (sec < 3600) {
        int m = sec / 60, s = sec % 60;
        return s == 0 ? m + "分钟" : m + "分钟" + s + "秒";
    }
    int h = sec / 3600, m = (sec % 3600) / 60, s = sec % 60;
    StringBuilder sb = new StringBuilder(h + "小时");
    if (m > 0) sb.append(m).append("分钟");
    if (s > 0) sb.append(s).append("秒");
    return sb.toString();
}
```

| 输入 | 输出 |
|---|---|
| `45` | `45秒` |
| `1320` | `22分钟` |
| `5400` | `1小时30分钟` |

### 7.3 组合显示

```java
String line1 = (remain >= 0 ? distance(remain) + " " : "") + road;
String line2 = (timeToDest >= 0 && distToDest >= 0)
        ? duration(timeToDest) + " | " + distance(distToDest) : "";
```

实测效果：
```text
44m 进入 中贸街
22分钟 | 8.6km
```

---

## 8. 转向图标

用 **`NaviOrderTypeNew`** 查表（AutoKit `util/e0.a(int)`，HashMap）。

- 键值范围：`1`~`72`，另有 `999` 与 `1000`~`1003`
- **`1` = 默认箭头**，键不存在时也回落到默认图标
- 实测 `NaviOrderTypeNew = 3` 对应一个转向图标，`1` 为直行

> ⚠️ 完整语义需对照 AutoKit 的图标资源确认。建议实现时先支持一小撮
> （左转 / 右转 / 直行 / 掉头 / 环岛 / 到达），其余用默认箭头兜底，
> 再用实车导航逐条验证。

---

## 9. 副屏显示方案（实测踩坑后结论）

### 9.1 两种副屏载体

| 载体 | 条件 | 说明 |
|---|---|---|
| `android.app.Presentation` | 系统里有 `FLAG_PRESENTATION` 的 `Display` | 原版做法 |
| `TYPE_APPLICATION_OVERLAY` 悬浮窗 | 任何设备 | playAuto 现有做法，便于无副屏时调试 |

判定副屏是否可用：

```java
display.getDisplayId() != 0 && (display.getFlags() & 8) != 0   // FLAG_PRESENTATION
```

### 9.2 开发调试用的虚拟副屏

```bash
# 创建 1280x480 @160dpi 副屏（带 FLAG_PRESENTATION，displayId=1）
adb shell su -c "settings put global overlay_display_devices 1280x480/160"

# 关闭
adb shell su -c "settings put global overlay_display_devices null"
```

### 9.3 ⚠️ 虚拟副屏的"镜像主屏"陷阱

**没有任何窗口**绘制到虚拟副屏时，它会显示**主屏镜像**。

| 副屏窗口数 | `screencap -d 1` | 现象 |
|---|---|---|
| 0 | **返回 0 字节** | 显示主屏镜像 |
| ≥1 | 正常 1280x480 | 显示窗口内容 |

→ **副屏必须常驻一个窗口**。想做"什么都不显示"，就铺一层**不透明纯黑**。

```java
// Presentation 窗口铺黑
getWindow().setBackgroundDrawable(new ColorDrawable(0xFF000000));
getWindow().setFormat(PixelFormat.OPAQUE);
rootView.setBackgroundColor(0xFF000000);
```

实测结果：`1280x480` 全部 `(0,0,0)`，共 `614400` 像素纯黑，镜像被完全盖住。

### 9.4 ⚠️ 不要复用 AutoKit 的 HUD 当黑底

`CustomHudInfoView.onDraw()` 在**无数据**时会走 `!x0` 分支绘制**占位引导图形**，
实测副屏留下 **40,491 个白像素**，不是纯黑。

要么拦截它的 `show()`，要么自己画。

### 9.5 推荐布局（上下两区）

```text
┌──────────────────────────────┐
│  导航区（无导航时为空）        │   权重 1
│  44m 进入 中贸街              │
│  22分钟 | 8.6km              │
├──────────────────────────────┤
│  歌词区（无歌词时为空）        │   权重 1
│  我 wish you love me...      │
└──────────────────────────────┘
```

用 `LinearLayout` + `layout_weight` 分区，`gravity=CENTER` 居中；
**不要依赖单一 `FrameLayout` 的 gravity**（实测会挤在一起）。

---

## 10. playAuto 现状与缺口

### ✅ 已实现

| 能力 | 位置 |
|---|---|
| `cmd 42` 帧解析与子类型分发 | `BoxProtocolSession.handleDashboardData()` |
| **subtype 1 媒体/歌词解析** | `onMediaUpdate(JSONObject)` → `MediaText` |
| **subtype 200 导航 JSON 解析** | `onNavigationUpdate(JSONObject)` |
| **导航/媒体字段增量合并** | `NavigationText.update()` / `MediaText.update()` |
| **导航字段容错解析** | 非法字段只跳过本字段，字符串数字也接受 |
| **`NaviStatus == 0` 防抖（3s）** | `NavigationText.shouldShow()` |
| **距离/时间格式化（m/km、秒/分钟/小时）** | `NavigationText.distance()` / `duration()` |
| **转向图标（可旋转箭头）** | `TurnIconView` + `NavigationText.iconRotation()` |
| **副屏文字层：导航两行 + 歌词** | `SecondaryScreenOverlay.setDashboardText()` |
| `cmd 8 / 506 / 507` 导航起止通知 | `onNavigationStarted()` / `onNavigationEnded()` |
| `cmd 25` 下发 `DashboardInfo` / `naviScreenInfo` | `BoxSetting.dashboardInfo()` / `naviScreenInfo()` |
| `naviScreenInfo` 未就绪补发 | `pendingNaviScreenInfo` + `flushNaviScreenInfo()` |
| `cmd 44` 副屏视频帧 | `onDashboardFrame` + `SecondaryScreenOverlay` |

### ❌ 缺口

| 缺口 | 说明 |
|---|---|
| 转向图标未一一对应原版资源 | 原版按 `NaviOrderTypeNew`（1~72/999/1000~1003）查 AutoKit drawable；现改为按 `NaviTurnAngle` 旋转的通用箭头，`NaviOrderTypeNew == 1` 视为直行 |
| `NaviTurnSide` 取值含义未验证 | 仅作为角度缺失时的兜底（1=右、2=左） |
| subtype `2/3` 专辑封面图未处理 | 需要图片解码与副屏图片区 |
| subtype `100` 来电信息未处理 | — |
| 歌词不滚动、不逐字高亮 | 盒子一次只下发一行，副屏只显示当前行 |
| 无 `MapThemeMode` / `cornerMasks` / `ViewAreaInfo` / `GNSSCapability` | 见 `box-protocol.md` §16 |

---

## 11. 实现步骤

### 步骤 1：补 subtype 1（媒体/歌词）

在 `BoxProtocolSession.handleDashboardData()` 中放开非 200 分支：

```java
private void handleDashboardData(byte[] payload) {
    if (payload.length < 4) { /* log */ return; }
    int subtype = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN).getInt();

    int end = payload.length;
    while (end > 4 && payload[end - 1] == 0) end--;

    if (subtype == 1) {
        String json = new String(payload, 4, end - 4, StandardCharsets.UTF_8);
        try {
            listener.onMediaUpdate(new JSONObject(json));
        } catch (JSONException e) { /* log，不中断 */ }
        return;
    }
    if (subtype == 200) { /* 现有逻辑 */ }
    // 2/3 封面、100 来电、202/203 暂不处理
}
```

新增 `MediaText`（仿 `NavigationText`，做增量合并）：

```java
final class MediaText {
    private String song = "", artist = "", album = "", lyrics = "";
    private int duration = -1, playTime = -1, playStatus = -1;

    void update(JSONObject data) throws JSONException {
        song     = str(data, "MediaSongName", song);
        artist   = str(data, "MediaArtistName", artist);
        album    = str(data, "MediaAlbumName", album);
        lyrics   = str(data, "MediaLyrics", lyrics);   // 空串要能清空歌词
        duration = num(data, "MediaSongDuration", duration);
        playTime = num(data, "MediaSongPlayTime", playTime);
        playStatus = num(data, "MediaPlayStatus", playStatus);
    }
}
```

> `MediaLyrics` 是**例外**：`""` 表示"该句结束/清空"，要允许空串覆盖。
> 其余字符串字段空串视为"未更新"。

### 步骤 2：放宽整数解析

`NavigationText.integer()` 目前类型不符就 `throw`，导致整包丢：

```java
private static int integer(JSONObject o, String key, int prev) {
    if (!o.has(key)) return prev;
    try {
        Object v = o.get(key);
        if (v instanceof Number) return ((Number) v).intValue();
        return (int) Double.parseDouble(String.valueOf(v).trim());   // 容错字符串
    } catch (Exception e) {
        return prev;                                                  // 静默跳过该字段
    }
}
```

### 步骤 3：`NaviStatus` 防抖

见 §6.2。建议 `CLEAR_DEBOUNCE_MS = 3000`。

### 步骤 4：格式化 + 图标

- 距离/时间用 §7 的两个静态方法
- 图标查表见 §8

### 步骤 5：副屏渲染

在现有 `SecondaryScreenOverlay` 之外，新增一个**纯文字副屏**（或在其上叠加文字层）：

```text
常驻不透明黑底（盖住虚拟副屏镜像 + 无数据时清空文字）
 ├─ 导航区：road / distance / time
 └─ 歌词区：lyrics
```

数据来源：`ConnectionService` 的 `NavigationText` + 新增 `MediaText`，
通过 `WindowListener` / 新的 `TextListener` 回调推给窗口。

### 步骤 6：显隐策略

| 条件 | 表现 |
|---|---|
| 无导航、无歌词 | 纯黑（不透明），窗口仍在 |
| 有歌词 | 歌词区显示 |
| 导航活跃（`NaviStatus>0` 或收到 506） | 导航区显示 |
| `NaviStatus==0` 且超过防抖阈值 | 导航区清空 |
| 盒子断开 | 全部清空，保持纯黑 |

---

## 12. 验证方法

### 12.1 抓 cmd 42 明细

用 Frida hook `BoxInterface.g.P(n)`（cmd 42 的唯一处理入口）：

```javascript
var G = Java.use('cn.manstep.phonemirrorBox.BoxInterface.g');
G.P.implementation = function (n) {
    var sub = n.b().getInt(0);                     // 子类型
    var len = n.d();                               // 负载长度
    var json = String(Java.use('java.lang.String').$new(n.c(), 4, len - 4, 'UTF-8'));
    send('[42] sub=' + sub + ' ' + json);
    return this.P(n);
};
```

### 12.2 抓导航状态

```javascript
var BC = Java.use('cn.manstep.phonemirrorBox.b1.c');
BC.L.implementation = function (v) {
    send('[NAV-STATE] L(' + v + ')  ' + (v === 1 ? '导航开始' : '导航结束'));
    return this.L(v);
};
```

### 12.3 判定表

| 抓包结果 | 结论 |
|---|---|
| 只有 `sub=1` | 媒体正常；**导航未在路线引导中**，或导航 App 不支持 CarPlay 导航 |
| `sub=1` + `sub=200` | 数据齐全，问题在渲染层 |
| `[NAV-STATE] L(1)` 但无 `sub=200` | 盒子认为导航开始了但没数据 → 换 Apple 地图再试 |
| `[NAV-STATE] L(0)` | 盒子认为没有导航在进行 |
| 什么 42 都没有 | 检查 `cmd 25` 是否下发了 `DashboardInfo` / `naviScreenInfo` |

### 12.4 快速自检命令

```bash
S=<设备地址>:5555

# 副屏是否被创建（应为 1）
adb -s $S shell "dumpsys window windows | grep -c mDisplayId=1"

# 抓副屏画面并统计像素（纯黑 = 614400 个 (0,0,0)）
adb -s $S shell su -c "screencap -d 1 -p /data/local/tmp/d1.png"
adb -s $S pull /data/local/tmp/d1.png /tmp/d1.png
python3 -c "from PIL import Image;i=Image.open('/tmp/d1.png');print(i.size,sorted(i.convert('RGB').getcolors(10**7),reverse=True)[:3])"
```

---

## 13. 踩坑清单

| # | 坑 | 应对 |
|---|---|---|
| 1 | 子类型 1/100/200 的 JSON 末尾有 `\0` | 解析前去掉尾部 0 字节 |
| 2 | 增量包被当成全量快照 | **必须字段级缓存合并**，`-1`/`null`/空串 = 未更新 |
| 3 | `NaviStatus` 在 2 和 0 之间快速跳变 | 清空要**防抖**（≥3s） |
| 4 | `MediaLyrics=""` 想清空歌词，但被当作"未更新"忽略 | 歌词字段**允许空串覆盖** |
| 5 | `supportFeatures` 为空就断言"不支持导航" | 不可靠，以能否收到 `42/200` 为准 |
| 6 | 只开地图没进路线引导 | 必须有真实路线引导才发 `200` |
| 7 | 虚拟副屏无窗口时镜像主屏 | 副屏**常驻不透明黑窗** |
| 8 | 复用 AutoKit HUD 当黑底 | 它有占位引导图形（40k+ 白像素），要拦截或自绘 |
| 9 | `FrameLayout` 单一 gravity 导致文字挤在一起 | 用 `LinearLayout` + `layout_weight` 分区 |
| 10 | `NavigationText.integer()` 遇非 `Number` 抛异常 | 放宽为容错解析，单字段失败不影响整包 |
| 11 | Frida 里 `n.a` 是**方法**不是字段 | 用 `getDeclaredField('a')` 反射 |
| 12 | Frida 里 `w0.f.c` 字段与方法 `c()` 同名 | 必须 `getDeclaredField('c')` |
| 13 | Frida 内部类不能用 `Outer.Inner.$new` | 用 `Java.use('android.widget.FrameLayout$LayoutParams')` |
| 14 | 360 加固冷启动检测 Frida 会 SIGSEGV | **先启动 App，再起 frida-server 后 attach** |

---

## 14. 参考资料（AutoKit 脱壳源码）

| 类 | 作用 |
|---|---|
| `BoxInterface.g` | 协议主类。`P(n)` = cmd 42 入口；`p1()` = cmd 25 下发；`f0(n)` = 命令分发 |
| `BoxInterface.d` | `BoxInfo`，含 `supportFeatures`（`F(String)` 查询） |
| `e1.d` | `BoxViewModel`：`C()` 来电、`D()` 媒体、`E()` 导航 |
| `FirstPage.w(i, i2, str)` | 事件码 → ViewModel 方法（60=媒体、61=来电、63=导航） |
| `d1.l` | `CustomHudPresentation`（HUD 渲染） |
| `d1.p` | `MyPresentation`（仪表视频 H.264） |
| `d1.s` | 测试用 Presentation（自定义显示区域） |
| `w0.f` | `PresentationManager`：`s()` 导航、`r()` 歌词、`q()` 来电 |
| `util.n0` | `b(int)` 距离格式化、`c(int)` 时间格式化 |
| `util.e0` | `a(int)` 转向图标映射（`NaviOrderTypeNew` → 资源 ID） |
| `widget.CustomHudInfoView` | HUD 绘制（`onDraw` 含占位引导图形） |
