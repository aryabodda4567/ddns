package org.ddns.util;


import java.time.Instant;

/**
 * Utility class for working with Unix timestamps.
 * Provides methods to get current time in Unix format
 * and compare time differences in minutes.
 */
public class TimeUtil {

    /**
     * Returns the current Unix timestamp in seconds.
     *
     * @return current Unix time in seconds
     */
    public static long getCurrentUnixTime() {
        return Instant.now().getEpochSecond();
    }

    /**
     * Checks if the difference between startTime and currentTime
     * is less than or equal to the given number of minutes.
     *
     * @param startTime   The start Unix timestamp in seconds.
     * @param currentTime The current Unix timestamp in seconds.
     * @param minutes     The threshold in minutes.
     * @return true if the difference is <= minutes, false otherwise.
     */
    public static boolean isWithinMinutes(long startTime, long currentTime, int minutes) {
        long differenceInSeconds = currentTime - startTime;
        long thresholdInSeconds = minutes * 60L;
        return differenceInSeconds <= thresholdInSeconds;
    }

    /**
     * Overloaded method: checks if the given timestamp is within N minutes from now.
     *
     * @param timestamp The Unix timestamp to check.
     * @param minutes   Number of minutes threshold.
     * @return true if timestamp is within minutes from current time; false otherwise.
     */
    public static boolean isWithinMinutesFromNow(long timestamp, int minutes) {
        long currentTime = getCurrentUnixTime();
        long difference = Math.abs(currentTime - timestamp);
        long threshold = minutes * 60L;
        return difference <= threshold;
    }
}

