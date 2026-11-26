package dev.fieldframe.api.status;

import java.util.List;
import java.util.Locale;

/**
 * A module's current state: a stable machine-readable code plus short arguments (e.g. lane "B",
 * fluid "minecraft:water"). Player-facing text is localized from {@code fieldframe.status.<code>} on the
 * client; the code itself never changes between versions.
 */
public record Status(String code, Severity severity, List<String> args) {
    public enum Severity {
        /** Doing useful work. */
        WORKING,
        /** Nothing to do; not a fault. */
        IDLE,
        /** Deliberately off (redstone, controller, config). */
        DISABLED,
        /** Wants to run but cannot; player action probably required. */
        BLOCKED,
        /** Definition missing, script error, invalid configuration. */
        FAULT
    }

    public Status {
        if (code == null || !code.matches("[A-Z0-9_]{1,48}")) {
            throw new IllegalArgumentException("Status code must be UPPER_SNAKE: " + code);
        }
        args = args == null ? List.of() : List.copyOf(args);
        if (args.size() > 4) {
            throw new IllegalArgumentException("too many status args");
        }
    }

    public static Status of(String code, Severity s, String... args) {
        return new Status(code, s, List.of(args));
    }

    public String translationKey() {
        return "fieldframe.status." + code.toLowerCase(Locale.ROOT);
    }

    /** Compact form used in logs and tooltips, e.g. {@code OUTPUT_FULL: Green storage}. */
    public String compact() {
        return args.isEmpty() ? code : code + ": " + String.join(", ", args);
    }

    public boolean is(Status other) {
        return other != null && code.equals(other.code);
    }
}
