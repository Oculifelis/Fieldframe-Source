package dev.fieldframe.api.id;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A validated namespaced identifier ({@code namespace:path}) used by every Fieldframe definition.
 * Loader-independent; adapters convert to and from the platform's resource location type.
 */
public final class FfId implements Comparable<FfId> {
    private static final Pattern NAMESPACE = Pattern.compile("[a-z0-9_.-]{1,64}");
    private static final Pattern PATH = Pattern.compile("[a-z0-9_./-]{1,128}");

    private final String namespace;
    private final String path;

    private FfId(String namespace, String path) {
        this.namespace = namespace;
        this.path = path;
    }

    public static FfId of(String namespace, String path) {
        if (namespace == null || !NAMESPACE.matcher(namespace).matches()) {
            throw new IllegalArgumentException("Invalid namespace '" + namespace + "'");
        }
        if (path == null || !PATH.matcher(path).matches()) {
            throw new IllegalArgumentException("Invalid path '" + path + "'");
        }
        return new FfId(namespace, path);
    }

    /** Parses {@code ns:path}; a bare path defaults to the {@code fieldframe} namespace. */
    public static FfId parse(String text) {
        Objects.requireNonNull(text, "id");
        int colon = text.indexOf(':');
        if (colon < 0) {
            return of("fieldframe", text);
        }
        return of(text.substring(0, colon), text.substring(colon + 1));
    }

    /** Returns null instead of throwing for untrusted input. */
    public static FfId tryParse(String text) {
        try {
            return text == null ? null : parse(text);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static FfId ff(String path) {
        return of("fieldframe", path);
    }

    public String namespace() {
        return namespace;
    }

    public String path() {
        return path;
    }

    @Override
    public int compareTo(FfId o) {
        int c = namespace.compareTo(o.namespace);
        return c != 0 ? c : path.compareTo(o.path);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof FfId other && namespace.equals(other.namespace) && path.equals(other.path);
    }

    @Override
    public int hashCode() {
        return namespace.hashCode() * 31 + path.hashCode();
    }

    @Override
    public String toString() {
        return namespace + ":" + path;
    }
}
