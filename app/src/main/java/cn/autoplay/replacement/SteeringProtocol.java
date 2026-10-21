package cn.autoplay.replacement;

import android.content.SharedPreferences;

final class SteeringProtocol {
    static final String PREFERENCE = "steering_protocol";
    static final String[] LABELS = {"默认", "长安 CS35Plus"};
    private static final String MIGRATED = "steering_protocol_migrated";

    private SteeringProtocol() { }

    static void migrate(SharedPreferences prefs) {
        if (prefs.getBoolean(MIGRATED, false)) return;
        SharedPreferences.Editor editor = prefs.edit();
        for (java.util.Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            String key = entry.getKey();
            if (key.startsWith("steering_vendor_") && entry.getValue() instanceof Integer) {
                String target = "steering_cs35plus_vendor_" + key.substring("steering_vendor_".length());
                if (!prefs.contains(target)) editor.putInt(target, (Integer) entry.getValue());
                editor.remove(key);
            }
        }
        editor.putBoolean(MIGRATED, true).apply();
    }

    static boolean vendorEnabled(SharedPreferences prefs) {
        return prefs.getInt(PREFERENCE, 0) == 1;
    }

    static String androidPrefix(SharedPreferences prefs) {
        return vendorEnabled(prefs) ? "steering_cs35plus_key_" : "steering_key_";
    }

    static String vendorPrefix() {
        return "steering_cs35plus_vendor_";
    }

    static int androidCommand(SharedPreferences prefs, int keyCode) {
        migrate(prefs);
        return prefs.getInt(androidPrefix(prefs) + keyCode, -1);
    }

    static int vendorCommand(SharedPreferences prefs, String code) {
        migrate(prefs);
        return vendorEnabled(prefs) ? prefs.getInt(vendorPrefix() + code, -1) : -1;
    }
}
