package org.ddns.web.user;

import org.ddns.db.DBUtil;
import spark.Request;
import spark.Response;

import java.time.Instant;
import java.util.UUID;

public final class SessionManager {

    public static final int SESSION_DURATION_SECONDS = 15 * 60;
    private static final String SESSION_COOKIE = "ddns_session";

    private SessionManager() {
    }

    public static long createSession(Response res) {
        String token = UUID.randomUUID().toString();
        long expiresAt = Instant.now().getEpochSecond() + SESSION_DURATION_SECONDS;

        DBUtil db = DBUtil.getInstance();
        db.putString(ConfigKey.SESSION_TOKEN.key(), token);
        db.putLong(ConfigKey.SESSION_EXPIRES_AT.key(), expiresAt);
        db.putInt(ConfigKey.IS_LOGGED_IN.key(), 1);

        res.cookie("/", SESSION_COOKIE, token, SESSION_DURATION_SECONDS, false, true);

        return expiresAt;
    }

    public static void clearSession(Response res) {
        DBUtil db = DBUtil.getInstance();
        db.delete(ConfigKey.SESSION_TOKEN.key());
        db.delete(ConfigKey.SESSION_EXPIRES_AT.key());
        db.putInt(ConfigKey.IS_LOGGED_IN.key(), 0);

        res.cookie("/", SESSION_COOKIE, "", 0, false, true);
    }

    public static boolean isSessionValid(Request req) {
        String cookieToken = req.cookie(SESSION_COOKIE);
        if (cookieToken == null || cookieToken.isBlank()) {
            return false;
        }

        DBUtil db = DBUtil.getInstance();
        String savedToken = db.getString(ConfigKey.SESSION_TOKEN.key());
        if (savedToken == null || !savedToken.equals(cookieToken)) {
            return false;
        }

        long expiresAt = db.getLong(ConfigKey.SESSION_EXPIRES_AT.key(), 0L);
        long now = Instant.now().getEpochSecond();

        if (expiresAt <= now) {
            db.delete(ConfigKey.SESSION_TOKEN.key());
            db.delete(ConfigKey.SESSION_EXPIRES_AT.key());
            db.putInt(ConfigKey.IS_LOGGED_IN.key(), 0);
            return false;
        }

        return true;
    }

    public static long getExpiresAt() {
        return DBUtil.getInstance().getLong(ConfigKey.SESSION_EXPIRES_AT.key(), 0L);
    }
}
