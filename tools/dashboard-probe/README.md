# 副屏数据抓包脚本

配套文档：`docs/dashboard-nav-lyrics.md`

用途：验证盒子是否真的在下发导航（`cmd 42 / 200`）和歌词（`cmd 42 / 1`）。

## 前置条件

1. 手机已 root，且 `frida-server` 与 Python `frida` 版本一致
2. **先启动 AutoKit，再启动 frida-server**（360 加固冷启动检测到 frida 会 SIGSEGV）
3. 盒子已插入、CarPlay 已连通

```bash
S=<设备IP>:5555

adb -s $S shell am force-stop cn.manstep.phonemirrorBox
adb -s $S shell am start -n cn.manstep.phonemirrorBox/.MainActivity
sleep 6
adb -s $S shell su -c "nohup /data/local/tmp/frida-server16 -D >/dev/null 2>&1 &"
```

## 脚本

### `capture_cmd42.js` —— 抓 cmd 42 全明细

```bash
python3 - <<'PY'
import time, frida
JS = open('tools/dashboard-probe/capture_cmd42.js', encoding='utf-8').read()
dev = frida.get_usb_device(timeout=10)
pid = dev.get_process('AutoKit').pid
s = dev.attach(pid)
sc = s.create_script(JS)
sc.on('message', lambda m, d: print(m.get('payload', m), flush=True))
sc.load(); time.sleep(30); s.detach()
PY
```

输出：

```
[media] {"MediaLyrics":"客串也别太潦草"}
[200!] {"NaviRoadName":"进入 中贸街","NaviOrderTypeNew":3}
=== cmd={"6":183,"42":30} 42sub={"1":30} nav200=0 media=4
```

判读：
- `42sub={"1":N}` → 只有媒体/歌词
- `42sub={"1":N,"200":M}` → 导航数据也在
- `nav200=0` → 盒子没发导航

### `watch_nav_state.js` —— 抓导航开始/结束通知

```bash
python3 - <<'PY'
import time, frida
JS = open('tools/dashboard-probe/watch_nav_state.js', encoding='utf-8').read()
dev = frida.get_usb_device(timeout=10)
pid = dev.get_process('AutoKit').pid
s = dev.attach(pid)
sc = s.create_script(JS)
sc.on('message', lambda m, d: print(m.get('payload', m), flush=True))
sc.load(); time.sleep(60); s.detach()
PY
```

输出：

```
[NAV-STATE 16:08:16] b1.c.L(0)  ← 导航结束
[NAV-STATE 16:12:03] b1.c.L(1)  ← 导航开始
```

对应 `cmd 8 / 506`（开始）与 `507`（结束）。

### `watch_send_25.js` —— 看 AutoKit 发给盒子的配置

输出包含 `naviScreenInfo` / `DashboardInfo` 的下发内容，
用于确认盒子是否拿到了副屏尺寸。

## 常见结论

| 现象 | 结论 |
|---|---|
| 只有 `42sub={"1":N}` | 导航未在 CarPlay 路线引导中，或导航 App 不支持 CarPlay 导航 |
| `[NAV-STATE] L(1)` 但无 `200` | 盒子认为导航开始了但采集不到 → 换 Apple 地图重试 |
| `[NAV-STATE] L(0)` 一直不变化 | 盒子认为始终没有导航在进行 |
| 完全无 `42` | 检查 `cmd 25` 的 `DashboardInfo` / `naviScreenInfo` 是否下发成功 |
