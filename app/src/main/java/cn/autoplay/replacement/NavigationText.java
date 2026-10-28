package cn.autoplay.replacement;

import org.json.JSONException;
import org.json.JSONObject;

final class NavigationText {
    private String road = "";
    private String destination = "";
    private int turnDistance = -1;
    private int destinationDistance = -1;
    private int destinationSeconds = -1;
    private String status = "等待盒子上报导航数据";

    void clear(String message) {
        road = destination = "";
        turnDistance = destinationDistance = destinationSeconds = -1;
        status = message;
    }

    void update(JSONObject data) throws JSONException {
        String nextRoad = string(data, "NaviRoadName", road);
        String nextDestination = string(data, "NaviDestinationName", destination);
        int nextTurnDistance = integer(data, "NaviRemainDistance", turnDistance);
        int nextDestinationDistance = integer(data, "NaviDistanceToDestination", destinationDistance);
        int nextDestinationSeconds = integer(data, "NaviTimeToDestination", destinationSeconds);
        int navigationStatus = integer(data, "NaviStatus", -1);
        if (navigationStatus == 0) {
            clear("导航已结束");
            return;
        }
        road = nextRoad;
        destination = nextDestination;
        turnDistance = nextTurnDistance;
        destinationDistance = nextDestinationDistance;
        destinationSeconds = nextDestinationSeconds;
        status = "等待道路及距离信息";
    }

    String text() {
        StringBuilder text = new StringBuilder("导航");
        if (turnDistance >= 0 || !road.isEmpty()) {
            text.append("\n");
            if (turnDistance >= 0) text.append(turnDistance).append(" 米");
            if (!road.isEmpty()) {
                if (turnDistance >= 0) text.append(" · ");
                text.append(road);
            }
        }
        if (!destination.isEmpty()) text.append("\n目的地：").append(destination);
        if (destinationDistance >= 0) text.append("\n剩余 ").append(destinationDistance).append(" 米");
        if (destinationSeconds >= 0) {
            text.append("\n预计剩余 ").append(destinationSeconds / 60).append(" 分 ")
                    .append(destinationSeconds % 60).append(" 秒");
        }
        if (text.length() == 2) text.append("\n").append(status);
        return text.toString();
    }

    private static String string(JSONObject data, String key, String previous) throws JSONException {
        if (!data.has(key)) return previous;
        Object value = data.get(key);
        if (!(value instanceof String)) throw new JSONException(key + " 必须为字符串");
        return (String) value;
    }

    private static int integer(JSONObject data, String key, int previous) throws JSONException {
        if (!data.has(key)) return previous;
        Object value = data.get(key);
        if (!(value instanceof Number)) throw new JSONException(key + " 必须为整数");
        double number = ((Number) value).doubleValue();
        if (Double.isNaN(number) || number < -1 || number > Integer.MAX_VALUE
                || number != Math.rint(number)) {
            throw new JSONException(key + " 超出有效整数范围");
        }
        return number == -1 ? previous : (int) number;
    }
}
