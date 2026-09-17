package dev.borges.shadow;

public final class AdminSession {
    private static boolean authenticated;

    private AdminSession() {}

    static void authenticate() {
        authenticated = true;
    }

    public static boolean isAuthenticated() {
        return authenticated;
    }

    public static void lock() {
        authenticated = false;
    }
}
