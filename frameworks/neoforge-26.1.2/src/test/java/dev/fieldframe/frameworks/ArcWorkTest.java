package dev.fieldframe.frameworks;

import dev.fieldframe.api.id.FfId;
import dev.fieldframe.api.process.ProcessRecipe;
import dev.fieldframe.api.resource.FluidVariant;
import dev.fieldframe.api.resource.ItemStore;
import dev.fieldframe.api.resource.ItemVariant;
import dev.fieldframe.api.resource.TagLookup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArcWorkTest {
    private static final ItemVariant IRON = ItemVariant.Simple.of("minecraft:iron_ingot");
    private static final ItemVariant RAW = ItemVariant.Simple.of("minecraft:raw_iron");
    private static final ItemVariant COAL = ItemVariant.Simple.of("minecraft:coal");
    private static final ItemVariant CHARCOAL = ItemVariant.Simple.of("minecraft:charcoal");
    private static final ItemVariant STEEL = ItemVariant.Simple.of("metallurgy:steel_ingot");
    private static final ItemVariant DIRT = ItemVariant.Simple.of("minecraft:dirt");

    /** #minecraft:coals holds coal and charcoal. */
    private static final TagLookup TAGS = new TagLookup() {
        @Override
        public boolean itemInTag(ItemVariant item, FfId tag) {
            return tag.toString().equals("minecraft:coals") && (item.sameAs(COAL) || item.sameAs(CHARCOAL));
        }

        @Override
        public boolean fluidInTag(FluidVariant fluid, FfId tag) {
            return false;
        }
    };

    private static final ArcAlloys.Alloy STEEL_ALLOY = new ArcAlloys.Alloy(FfId.of("test", "steel"),
            List.of(new ArcAlloys.Part(FfId.parse("minecraft:iron_ingot"), false, 2), new ArcAlloys.Part(FfId.parse("minecraft:coals"), true, 1)),
            new ArcAlloys.Part(FfId.parse("metallurgy:steel_ingot"), false, 2));

    private static final List<ProcessRecipe> HEATING = List.of(new ProcessRecipe(FfId.of("test", "raw_iron"), FfId.ff("heating"),
            List.of(new ProcessRecipe.ItemInput((v, t) -> v.sameAs(RAW), 1, true, "raw iron")), null,
            List.of(new ProcessRecipe.ItemResult(IRON, 1, 1.0)), null, 100, 20));

    private static ItemVariant item(FfId id) {
        return new ItemVariant.Simple(id, "", 64);
    }

    private static ArcWork.Result run(ItemStore in, ItemStore out, int budget) {
        return ArcWork.run(in, out, List.of(STEEL_ALLOY), HEATING, TAGS, ArcWorkTest::item, budget, () -> 0.5);
    }

    @AfterEach
    void reset() {
        ArcAlloys.load(Map.of(), id -> true);
    }

    @Test
    void alloysFirstThenSmelts() {
        Store in = new Store(4);
        Store out = new Store(4);
        in.insert(RAW, 4, false);
        in.insert(IRON, 2, false);
        in.insert(CHARCOAL, 1, false);
        ArcWork.Result r = run(in, out, 16);
        assertEquals(1, r.batches());
        assertEquals(4, r.smelted());
        assertEquals(7, r.itemsIn());
        assertEquals(2, out.countOf(STEEL));
        assertEquals(4, out.countOf(IRON), "the smelted iron goes to Green storage, not into the alloy");
        assertEquals(0, in.totalCount());
    }

    @Test
    void budgetLimitsTheArc() {
        Store in = new Store(4);
        Store out = new Store(4);
        in.insert(IRON, 20, false);
        in.insert(COAL, 10, false);
        in.insert(RAW, 10, false);
        ArcWork.Result r = run(in, out, 10);
        assertEquals(3, r.batches(), "three batches of three items fit in ten");
        assertEquals(1, r.smelted(), "the last item is smelted");
        assertEquals(6, out.countOf(STEEL));
        assertEquals(10 + 30 - 10, in.totalCount());
    }

    @Test
    void conservesWhenGreenIsFull() {
        Store in = new Store(4);
        Store out = new Store(1);
        out.insert(DIRT, 64, false);
        in.insert(IRON, 2, false);
        in.insert(COAL, 1, false);
        in.insert(RAW, 5, false);
        ArcWork.Result r = run(in, out, 64);
        assertTrue(!r.any(), "nothing fits, so nothing is taken");
        assertEquals(8, in.totalCount());
        assertEquals(64, out.totalCount());
    }

    @Test
    void partialRoomTakesOnlyWhatFits() {
        Store in = new Store(4);
        Store out = new Store(1);
        out.insert(IRON, 61, false);
        in.insert(RAW, 10, false);
        ArcWork.Result r = run(in, out, 64);
        assertEquals(3, r.smelted());
        assertEquals(7, in.countOf(RAW));
        assertEquals(64, out.countOf(IRON));
    }

    @Test
    void dataFilesReplaceAndRemove() {
        ArcAlloys.register(STEEL_ALLOY);
        List<String> errors = ArcAlloys.load(Map.of(
                FfId.of("test", "tartarite"), "{\"inputs\": [{\"item\": \"#c:ingots/adamantine\"}, {\"item\": \"#c:ingots/atlarus\"}],"
                        + " \"result\": {\"item\": \"metallurgy:tartarite_ingot\", \"count\": 2}}",
                FfId.of("test", "steel"), "{\"remove\": true}",
                FfId.of("test", "bad"), "{\"inputs\": [], \"result\": {\"item\": \"x:y\"}}",
                FfId.of("test", "gone"), "{\"inputs\": [{\"item\": \"x:a\"}], \"result\": {\"item\": \"x:missing\"}}"),
                id -> !id.path().equals("missing"));
        assertEquals(1, errors.size(), errors.toString());
        assertEquals(1, ArcAlloys.all().size(), ArcAlloys.all().toString());
        ArcAlloys.Alloy t = ArcAlloys.all().get(0);
        assertEquals("metallurgy:tartarite_ingot", t.result().id().toString());
        assertEquals(2, t.result().count());
        assertTrue(t.inputs().get(0).tag());
        String json = ArcAlloys.toJson();
        ArcAlloys.acceptSync("{bad");
        assertEquals(List.of(t), ArcAlloys.all());
        ArcAlloys.acceptSync(json);
        assertEquals(List.of(t), ArcAlloys.all(), "the client copy matches the server's");
    }

    /** A plain slot store: stacks of one variant up to 64, filling same-variant slots first. */
    static final class Store implements ItemStore {
        private final ItemVariant[] v;
        private final int[] n;

        Store(int slots) {
            v = new ItemVariant[slots];
            n = new int[slots];
        }

        @Override
        public int slots() {
            return v.length;
        }

        @Override
        public ItemVariant variant(int slot) {
            return n[slot] > 0 ? v[slot] : null;
        }

        @Override
        public int count(int slot) {
            return n[slot];
        }

        @Override
        public int insert(ItemVariant item, int amount, boolean simulate) {
            int[] c = n.clone();
            ItemVariant[] w = v.clone();
            int left = amount;
            for (int pass = 0; pass < 2 && left > 0; pass++) {
                for (int s = 0; s < w.length && left > 0; s++) {
                    boolean ok = pass == 0 ? c[s] > 0 && w[s].sameAs(item) : c[s] == 0;
                    if (ok) {
                        int t = Math.min(left, 64 - c[s]);
                        w[s] = item;
                        c[s] += t;
                        left -= t;
                    }
                }
            }
            if (!simulate) {
                System.arraycopy(c, 0, n, 0, c.length);
                System.arraycopy(w, 0, v, 0, w.length);
            }
            return amount - left;
        }

        @Override
        public int extractSlot(int slot, int amount, boolean simulate) {
            int t = Math.min(amount, n[slot]);
            if (!simulate) {
                n[slot] -= t;
            }
            return t;
        }
    }
}
