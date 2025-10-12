package org.ddns.util;



/**
 * Utility class for printing colored console messages.
 * <p>
 * Supports success (green) and failure (red) messages.
 * Works on terminals that support ANSI escape codes
 * (Linux, macOS, and Windows 10+).
 */
public final class ConsolePrinter {

    // Prevent instantiation
    private ConsolePrinter() {}

    // ANSI color codes
    private static final String RESET = "\u001B[0m";
    private static final String GREEN = "\u001B[32m";
    private static final String RED = "\u001B[31m";
    private static final String YELLOW = "\u001B[33m";
    private static final String BLUE   = "\u001B[34m";

    /**
     * Prints a success message in green.
     *
     * @param message The message to print
     */
    public static void printSuccess(String message) {
        System.out.println(GREEN + message + RESET);
    }

    /**
     * Prints a failure message in red.
     *
     * @param message The message to print
     */
    public static void printFail(String message) {
        System.out.println(RED + message + RESET);
    }
    /**
     * Prints an informational message in blue.
     *
     * @param message The message to print
     */
    public static void printInfo(String message) {
        System.out.println(BLUE + message + RESET);
    }

    /**
     * Prints a warning message in yellow.
     *
     * @param message The message to print
     */
    public static void printWarning(String message) {
        System.out.println(YELLOW + message + RESET);
    }
}

