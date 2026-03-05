package org.ddns.web.services.config;

/**
 * Runtime web mode used to split bootstrap-only flow from normal node flow.
 */
public enum AppMode {
    UNSET,
    NODE,
    BOOTSTRAP;

    public static AppMode from(String raw) {
        if (raw == null || raw.isBlank()) {
            return UNSET;
        }
        try {
            return AppMode.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return UNSET;
        }
    }
}
