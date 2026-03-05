package org.ddns.web.user;

/**
 * Storage keys used by web user/session layer.
 */
public enum ConfigKey {

    IS_LOGGED_IN("is_logged_in"),
    USERNAME("username"),
    PASSWORD("password"),
    EMAIL("email"),
    FIRSTNAME("first_name"),
    LASTNAME("last_name"),
    SESSION_TOKEN("session_token"),
    SESSION_EXPIRES_AT("session_expires_at");

    private final String key;

    ConfigKey(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}
