package dev.ronin.gwentmousebridge;

import android.content.Context;
import android.content.SharedPreferences;

final class BridgePrefs {
    static final String PREFS = "bridge";
    static final String KEY_ENABLED = "enabled";
    static final String KEY_SHOW_CURSOR = "show_cursor";
    static final String KEY_SENSITIVITY = "sensitivity_percent";
    static final String KEY_READER_STATUS = "reader_status";
    static final String KEY_READER_FRAME_COUNT = "reader_frame_count";
    static final String KEY_READER_MOTION_FRAME_COUNT = "reader_motion_frame_count";
    static final String KEY_READER_LAST_FRAME_TIME = "reader_last_frame_time";

    static final String GWENT_PACKAGE = "com.cdprojektred.gwent";
    static final String PREFERRED_MOUSE = "HUAWEI Mouse CD26 SE Mouse";

    private BridgePrefs() {}

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean enabled(Context context) {
        return prefs(context).getBoolean(KEY_ENABLED, false);
    }

    static boolean showCursor(Context context) {
        return prefs(context).getBoolean(KEY_SHOW_CURSOR, true);
    }

    static float sensitivity(Context context) {
        return prefs(context).getInt(KEY_SENSITIVITY, 125) / 100f;
    }
}
