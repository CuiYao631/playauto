package cn.autoplay.replacement;

import org.json.JSONException;
import org.json.JSONObject;

public final class NavigationTextSelfTest {
    public static void main(String[] args) throws Exception {
        NavigationText state = new NavigationText();
        check(state.text().contains("等待盒子上报导航数据"), "Initial waiting state");
        state.update(new JSONObject("{\"NaviStatus\":1,\"NaviRoadName\":\"Road A\","
                + "\"NaviRemainDistance\":100,\"NaviDestinationName\":\"Destination\","
                + "\"NaviDistanceToDestination\":2000,\"NaviTimeToDestination\":125}"));
        check(state.text().equals("导航\n100 米 · Road A\n目的地：Destination"
                + "\n剩余 2000 米\n预计剩余 2 分 5 秒"), "Complete navigation text");
        state.update(new JSONObject("{\"NaviRemainDistance\":50}"));
        check(state.text().contains("50 米 · Road A"), "Partial update preserves road");
        String previous = state.text();
        state.update(new JSONObject("{\"NaviRemainDistance\":-1}"));
        check(state.text().equals(previous), "Unavailable value preserves state");
        for (String invalid : new String[]{"\"bad\"", "null", "-2", "1.5", "2147483648"}) {
            try {
                state.update(new JSONObject("{\"NaviRoadName\":\"Changed\","
                        + "\"NaviRemainDistance\":" + invalid + "}"));
                throw new AssertionError("Invalid distance accepted: " + invalid);
            } catch (JSONException expected) {
                check(state.text().equals(previous), "Invalid update is atomic");
            }
        }
        state.update(new JSONObject("{\"NaviStatus\":0}"));
        check(state.text().equals("导航\n导航已结束"), "End clears all navigation data");
        state.update(new JSONObject("{\"NaviRoadName\":\"Road B\"}"));
        check(!state.text().contains("Destination"), "New navigation does not reuse destination");
        state.clear("Disconnected");
        check(state.text().equals("导航\nDisconnected"), "Disconnect clears all navigation data");
        state.clear("手机已连接，等待盒子上报导航数据");
        check(state.text().equals("导航\n手机已连接，等待盒子上报导航数据"),
                "Reconnect replaces disconnected prompt without requiring navigation JSON");
        state.update(new JSONObject("{\"NaviRoadName\":\"Road C\"}"));
        check(state.text().equals("导航\nRoad C"), "Navigation replaces connected waiting prompt");
        System.out.println("NavigationTextSelfTest passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
