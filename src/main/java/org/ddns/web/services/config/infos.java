package org.ddns.web.services.config;

import org.ddns.constants.ConfigKey;
import org.ddns.db.DBUtil;

/**
 * Lightweight acceptance-state accessor used by web handlers.
 */
public class infos {

    /**
     * Backward-compatible method name used in older call sites.
     */
    public boolean IsAccepted() {
        return isAccepted();
    }

    /**
     * @return true when node acceptance flag is set in config store.
     */
    public boolean isAccepted() {
        return DBUtil.getInstance().getInt(ConfigKey.IS_ACCEPTED.toString(), 0) == 1;
    }
}
