package dev.fieldframe.core.material;

import dev.fieldframe.api.process.MachineData;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Which mod's item the Tag Unifier turns common-tag items into: the first namespace in this list that has an item in
 * the tag wins. Default: Metallurgy, then vanilla, then Fieldframe. Java and KubeJS contributors
 * ({@link MachineData#addUnificationContributor}) can put other mods first; a Tag Unifier's own "preferred" setting
 * overrides the list for that module. Recomputed after every data reload.
 */
public final class Unification {
    public static final List<String> DEFAULT = List.of("metallurgy", "minecraft", "fieldframe");

    private static volatile List<String> preferred;

    private Unification() {
    }

    static final class Sink implements MachineData.UnificationSink {
        final List<String> list = new ArrayList<>(DEFAULT);

        @Override
        public void prefer(String namespace) {
            String ns = clean(namespace);
            list.remove(ns);
            list.add(0, ns);
        }

        @Override
        public void avoid(String namespace) {
            list.remove(clean(namespace));
        }

        private static String clean(String ns) {
            String s = ns == null ? "" : ns.trim().toLowerCase(Locale.ROOT);
            if (!s.matches("[a-z0-9_.-]+")) {
                throw new IllegalArgumentException("bad namespace '" + ns + "'");
            }
            return s;
        }
    }

    /** The preferred namespaces, most preferred first. */
    public static List<String> preferred() {
        List<String> p = preferred;
        if (p == null) {
            Sink sink = new Sink();
            for (Consumer<MachineData.UnificationSink> c : MachineData.unificationContributors()) {
                try {
                    c.accept(sink);
                } catch (RuntimeException ignored) {
                    // A broken script entry leaves the list as it was.
                }
            }
            p = List.copyOf(sink.list);
            preferred = p;
        }
        return p;
    }

    /** Data was reloaded: contributors run again on next use. */
    public static void invalidate() {
        preferred = null;
    }
}
