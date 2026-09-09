package io.onek8s.dbjava;

/** Configuration, all of it from the environment — as in the .NET application. */
public final class Env {

    private Env() {
    }

    /**
     * Unset and set-but-empty are the same thing here: both mean "the chart did
     * not give us one".
     */
    public static String value(String name) {
        String value = System.getenv(name);

        return value == null || value.isEmpty() ? null : value;
    }

    /** The same, with the fallback the page prints when nothing was set. */
    public static String value(String name, String fallback) {
        String value = value(name);

        return value == null ? fallback : value;
    }
}
