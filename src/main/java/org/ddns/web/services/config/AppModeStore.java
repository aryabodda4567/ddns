package org.ddns.web.services.config;

import org.ddns.db.DBUtil;

/**
 * Persists and loads the web app mode from node config storage.
 */
public final class AppModeStore {

    private static final String APP_MODE_KEY = "WEB_APP_MODE";

    private AppModeStore() {
    }

    public static AppMode getMode() {
        return AppMode.from(DBUtil.getInstance().getString(APP_MODE_KEY));
    }

    public static void setMode(AppMode mode) {
        if (mode == null) {
            mode = AppMode.UNSET;
        }
        DBUtil.getInstance().putString(APP_MODE_KEY, mode.name());
    }
}
