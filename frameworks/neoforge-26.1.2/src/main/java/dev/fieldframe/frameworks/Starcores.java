package dev.fieldframe.frameworks;

import dev.fieldframe.api.world.WorldPos;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which Starcore a module socket belongs to, by the socket's address. A formed Starcore binds its sockets; Starcore
 * modules on a socket look their plasma up through {@code ctx.world().position()}, so they need no link of their own.
 * Server side only.
 */
public final class Starcores {
    private Starcores() {
    }

    private static final Map<WorldPos, Plasma> BY_SOCKET = new ConcurrentHashMap<>();

    /** The plasma of the Starcore the socket at {@code pos} belongs to, or null. */
    public static Plasma at(WorldPos pos) {
        return pos == null ? null : BY_SOCKET.get(pos);
    }

    static void bind(Collection<WorldPos> sockets, Plasma plasma) {
        for (WorldPos p : sockets) {
            BY_SOCKET.put(p, plasma);
        }
    }

    static void unbind(Collection<WorldPos> sockets, Plasma plasma) {
        for (WorldPos p : sockets) {
            BY_SOCKET.remove(p, plasma);
        }
    }

    static void clear() {
        BY_SOCKET.clear();
    }
}
