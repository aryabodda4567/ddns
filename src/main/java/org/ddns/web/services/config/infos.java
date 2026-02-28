package org.ddns.web.services.config;

import org.ddns.constants.ConfigKey;
import org.ddns.db.DBUtil;

public class infos {
    public boolean IsAccepted() {
        return isAccepted();
    }

    public boolean isAccepted() {
        return DBUtil.getInstance().getInt(ConfigKey.IS_ACCEPTED.toString(), 0) == 1;
    }

}
