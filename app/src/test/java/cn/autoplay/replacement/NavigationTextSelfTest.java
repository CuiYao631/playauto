package cn.autoplay.replacement;

import org.json.JSONObject;

/**
 * 导航文字合并、格式化与结束防抖的自检。
 *
 * <p>注意：本类不参与 {@code build-apk.sh} 的自动执行（脚本只跑
 * {@link ProtocolFrameSelfTest}），因为纯 JVM 下的 {@code android.jar} 里
 * {@code org.json} 是抛 {@code Stub!} 的桩实现。需要在设备/带真实 json 库的
 * 环境里手动运行。
 */
public final class NavigationTextSelfTest {
    public static void main(String[] args) throws Exception {
        NavigationText state = new NavigationText();
        check(state.text().contains("等待盒子上报导航数据"), "Initial waiting state");

        state.update(new JSONObject("{\"NaviStatus\":2,\"NaviRoadName\":\"Road A\","
                + "\"NaviRemainDistance\":100,\"NaviDestinationName\":\"Destination\","
                + "\"NaviDistanceToDestination\":2000,\"NaviTimeToDestination\":125}"), 1000);
        check("100m Road A".equals(state.primaryLine()), "Primary line formats distance");
        check("2分钟5秒 | 2km".equals(state.secondaryLine()), "Secondary line formats time/distance");

        state.update(new JSONObject("{\"NaviRemainDistance\":50}"), 2000);
        check("50m Road A".equals(state.primaryLine()), "Partial update preserves road");

        String previous = state.primaryLine();
        state.update(new JSONObject("{\"NaviRemainDistance\":-1}"), 3000);
        check(previous.equals(state.primaryLine()), "Unavailable value preserves state");

        // 容错解析：非法字段只跳过该字段，不清空其它字段
        state.update(new JSONObject("{\"NaviRemainDistance\":\"bad\","
                + "\"NaviRoadName\":\"Road B\"}"), 4000);
        check("Road B".equals(state.primaryLine()) || state.primaryLine().endsWith("Road B"),
                "Malformed field skipped without dropping others");

        state.update(new JSONObject("{\"NaviTimeToDestination\":\"1320\"}"), 5000);
        check(state.secondaryLine().startsWith("22分钟"), "Numeric string accepted");
        check(state.secondaryLine().endsWith("2km"), "Distance preserved alongside");

        // NaviStatus=0 防抖：3 秒内仍显示，之后隐藏
        state.update(new JSONObject("{\"NaviStatus\":0}"), 6000);
        check(state.shouldShow(6000 + 100), "End is debounced for 3s");
        check(!state.shouldShow(6000 + NavigationText.CLEAR_DEBOUNCE_MS + 100),
                "End clears after debounce window");

        // 导航重新开始会取消结束标记
        state.markStarted(20000);
        check(state.shouldShow(20000), "Restart cancels pending end");
        check(!state.endPending(), "Restart clears end flag");

        // 回归：建链瞬间盒子会连发 NaviStatus 0/2 交替，若最后落地的是 0，
        // 后续只要还有道路/距离增量，导航区就必须继续显示，不能永久隐藏。
        NavigationText flap = new NavigationText();
        long t = 1000;
        for (int status : new int[]{0, 2, 0, 2, 0}) {
            flap.update(new JSONObject("{\"NaviStatus\":" + status + "}"), t += 10);
        }
        check(flap.endPending(), "Trailing NaviStatus=0 leaves end pending");
        flap.update(new JSONObject("{\"NaviRoadName\":\"进入 中贸街\"}"), t += 1000);
        check(!flap.endPending(), "Content packet clears pending end");
        check(flap.shouldShow(t + 5000), "Nav area stays visible while content flows");
        flap.update(new JSONObject("{\"NaviRemainDistance\":45}"), t += 10000);
        check(flap.shouldShow(t + 60000), "Long standstill keeps last value, no data for 60s");
        check("45m 进入 中贸街".equals(flap.primaryLine()), "Standstill keeps last distance");
        // 真正结束仍然生效：只有 NaviStatus=0 且没有后续内容时才隐藏
        NavigationText stopped = new NavigationText();
        stopped.update(new JSONObject("{\"NaviRoadName\":\"Road A\","
                + "\"NaviRemainDistance\":100}"), 1000);
        stopped.update(new JSONObject("{\"NaviStatus\":0}"), 2000);
        check(!stopped.shouldShow(2000 + NavigationText.CLEAR_DEBOUNCE_MS + 100),
                "Genuine end still hides after debounce");

        check(NavigationText.hasNavigationContent(new JSONObject("{\"NaviRemainDistance\":1}")),
                "content detector sees distance");
        check(!NavigationText.hasNavigationContent(new JSONObject("{\"NaviStatus\":0}")),
                "content detector ignores bare status");

        // 真机回归：2026-11-24 实抓原样数据。旧的 CarPlay 编码会把三者全画错方向。
        NavigationText sampled = new NavigationText();
        sampled.update(new JSONObject("{\"NaviRoadName\":\"进入 长安北路辅路\","
                + "\"NaviOrderType\":6,\"NaviTurnAngle\":3,\"NaviTurnSide\":2,"
                + "\"NaviManeuverType\":48,\"NaviOrderTypeNew\":7}"), 1000);
        check(sampled.maneuver() == Maneuver.SHARP_RIGHT, "实抓 7 → 右后");
        check(sampled.primaryLine().equals("进入 长安北路辅路"), "路名进入主行");

        sampled.update(new JSONObject("{\"NaviRoadName\":\"进入 中贸街\","
                + "\"NaviOrderType\":6,\"NaviTurnAngle\":4,"
                + "\"NaviManeuverType\":4,\"NaviOrderTypeNew\":8}"), 2000);
        check(sampled.maneuver() == Maneuver.STRAIGHT, "实抓 8 → 直行变体");

        sampled.update(new JSONObject("{\"NaviRoadName\":\"中贸街\","
                + "\"NaviOrderType\":6,\"NaviTurnAngle\":2,\"NaviTurnSide\":1,"
                + "\"NaviManeuverType\":1,\"NaviOrderTypeNew\":2}"), 3000);
        check(sampled.maneuver() == Maneuver.LEFT, "实抓 2 → 左转");
        check("左转".equals(sampled.maneuver().label()), "转向标签可用于排查");

        state.clear("Disconnected");
        check(state.text().equals("导航\nDisconnected"), "Disconnect clears all navigation data");

        // 格式化表
        check("53m".equals(NavigationText.distance(53)), "distance 53m");
        check("350m".equals(NavigationText.distance(350)), "distance 350m");
        check("5.6km".equals(NavigationText.distance(5600)), "distance 5.6km");
        check("8km".equals(NavigationText.distance(8000)), "distance 8km");
        check("45秒".equals(NavigationText.duration(45)), "duration 45s");
        check("22分钟".equals(NavigationText.duration(1320)), "duration 22min");
        check("1小时30分钟".equals(NavigationText.duration(5400)), "duration 1h30m");

        // 媒体/歌词增量合并
        MediaText media = new MediaText();
        media.update(new JSONObject("{\"MediaSongName\":\"Song\",\"MediaArtistName\":\"Artist\","
                + "\"MediaLyrics\":\"line one\"}"));
        check("line one".equals(media.lyricLine()), "Lyrics shown when present");
        media.update(new JSONObject("{\"MediaSongPlayTime\":210534}"));
        check("line one".equals(media.lyricLine()), "Progress packet keeps lyrics");
        media.update(new JSONObject("{\"MediaLyrics\":\"\"}"));
        check("Song · Artist".equals(media.lyricLine()), "Empty lyrics falls back to song");
        media.clear();
        check(media.lyricLine().isEmpty(), "Clear drops media state");

        System.out.println("NavigationTextSelfTest passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
