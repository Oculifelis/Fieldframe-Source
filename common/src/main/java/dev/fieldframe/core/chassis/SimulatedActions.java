package dev.fieldframe.core.chassis;

import dev.fieldframe.api.face.LocalFace;
import dev.fieldframe.api.id.FfId;
import dev.fieldframe.api.resource.FluidVariant;
import dev.fieldframe.api.resource.ItemStore;
import dev.fieldframe.api.resource.ItemVariant;
import dev.fieldframe.api.world.WorkTarget;
import dev.fieldframe.api.world.WorldActions;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Headless {@link WorldActions} over a sparse map of synthetic blocks keyed by work target. Deterministic and
 * side-effect free outside itself; used by unit tests. Positions are compared in face-local coordinates, which is
 * enough for single-face scenarios.
 */
public final class SimulatedActions implements WorldActions {
    /** A synthetic block. {@code tool} null = harvestable by hand. */
    public static final class Block {
        public FfId id;
        public float hardness = 1;
        public FfId tool;
        public List<Drop> drops = new ArrayList<>();
        public boolean blockEntity;
        public boolean crop;
        public boolean mature;
        public ItemVariant plant;
        public FfId fluid;
        public boolean source;
        public boolean flowingWater;
        public boolean replaceable;

        public static Block of(String id, ItemVariant drop) {
            Block b = new Block();
            b.id = FfId.parse(id);
            if (drop != null) {
                b.drops.add(new Drop(drop, 1));
            }
            return b;
        }
    }

    public final Map<String, Block> blocks = new HashMap<>();
    public final Set<String> protectedTargets = new HashSet<>();
    public final Set<String> unloadedTargets = new HashSet<>();
    public final Map<LocalFace, List<Drop>> groundItems = new EnumMap<>(LocalFace.class);
    public final Map<LocalFace, Integer> entities = new EnumMap<>(LocalFace.class);
    public final Map<LocalFace, Double> entitySpeed = new EnumMap<>(LocalFace.class);
    public final List<Drop> ejected = new ArrayList<>();
    public final List<String> marks = new ArrayList<>();
    public boolean policyDisabled;
    /** Loot returned by every fishing roll (empty = nothing biting). */
    public final List<Drop> fishLoot = new ArrayList<>();
    public int lastFishingLuck = -1;
    /** Experience points lying in front of each face. */
    public final Map<LocalFace, Integer> xp = new EnumMap<>(LocalFace.class);
    public int breaks;
    public int moved;

    public static String key(WorkTarget t) {
        return t.face().key() + ":" + t.depth() + ":" + t.right() + ":" + t.up();
    }

    public SimulatedActions put(WorkTarget t, Block b) {
        blocks.put(key(t), b);
        return this;
    }

    public Block at(WorkTarget t) {
        return blocks.get(key(t));
    }

    @Override
    public String actorName() {
        return "[Fieldframe] test";
    }

    private Outcome access(WorkTarget t) {
        if (unloadedTargets.contains(key(t))) {
            return Outcome.UNLOADED;
        }
        if (protectedTargets.contains(key(t))) {
            return Outcome.PROTECTED;
        }
        return policyDisabled ? Outcome.DISABLED_BY_POLICY : Outcome.OK;
    }

    /** Temperatures of the block in front of each face (depth 1); everything else is {@link #ambient}. */
    /** Mobs in front of each face for the Mob Grinder: each attack kills one (with its drops left for collection). */
    public final Map<LocalFace, Integer> mobs = new EnumMap<>(LocalFace.class);
    /** Damage a weapon takes per attack (0 = none). */
    public int weaponWear = 1;

    @Override
    public AttackResult attackMobs(LocalFace face, int range, ItemVariant weapon, boolean named, int maxTargets) {
        int n = Math.min(maxTargets, mobs.getOrDefault(face, 0));
        if (n <= 0) {
            return new AttackResult(Outcome.NOTHING_TO_DO, 0, 0, weapon);
        }
        mobs.put(face, mobs.get(face) - n);
        return new AttackResult(Outcome.OK, n, n, weapon);
    }

    /** Animals on Treadmills, by face. */
    public final Map<LocalFace, WalkingAnimal> animals = new EnumMap<>(LocalFace.class);

    @Override
    public WalkingAnimal animalOnTreadmill(LocalFace face) {
        return animals.get(face);
    }

    public final Map<LocalFace, Integer> temperatures = new EnumMap<>(LocalFace.class);
    /** Temperatures of single targets (any depth and offset), by {@link #key}; checked before {@link #temperatures}. */
    public final Map<String, Integer> targetTemperatures = new java.util.HashMap<>();
    public int ambient = 290;

    @Override
    public int temperature(WorkTarget t) {
        if (unloadedTargets.contains(key(t))) {
            return -1;
        }
        Integer k = targetTemperatures.get(key(t));
        if (k == null && t.depth() == 1) {
            k = temperatures.get(t.face());
        }
        return k != null ? k : ambient;
    }

    @Override
    public BlockInfo probe(WorkTarget t) {
        if (unloadedTargets.contains(key(t))) {
            return BlockInfo.unloaded();
        }
        Block b = at(t);
        Outcome acc = protectedTargets.contains(key(t)) ? Outcome.PROTECTED : Outcome.OK;
        if (b == null) {
            return new BlockInfo(acc, FfId.parse("minecraft:air"), true, true, 0, false, false, false, null, null, false, false);
        }
        return new BlockInfo(acc, b.id, false, b.replaceable, b.hardness, b.blockEntity, b.crop, b.mature, b.plant,
                b.fluid, b.source, b.flowingWater);
    }

    @Override
    public BreakResult breakBlock(WorkTarget t, ItemVariant tool, boolean requireCorrectTool) {
        Outcome acc = access(t);
        if (!acc.ok()) {
            return BreakResult.fail(acc, tool);
        }
        Block b = at(t);
        if (b == null || b.fluid != null) {
            return BreakResult.fail(Outcome.AIR, tool);
        }
        if (b.hardness < 0) {
            return BreakResult.fail(Outcome.UNBREAKABLE, tool);
        }
        boolean correct = b.tool == null || tool != null && tool.id().equals(b.tool);
        if (requireCorrectTool && !correct) {
            return BreakResult.fail(Outcome.WRONG_TOOL, tool);
        }
        blocks.remove(key(t));
        breaks++;
        return new BreakResult(Outcome.OK, correct ? b.drops : List.of(), tool);
    }

    @Override
    public Outcome place(WorkTarget t, ItemVariant item) {
        Outcome acc = access(t);
        if (!acc.ok()) {
            return acc;
        }
        Block b = at(t);
        if (b != null && !b.replaceable) {
            return Outcome.NOT_REPLACEABLE;
        }
        if (!item.id().path().endsWith("_block") && !item.id().path().endsWith("seeds")
                && !item.id().path().equals("cobblestone")) {
            return Outcome.NOT_PLACEABLE;
        }
        Block placed = Block.of(item.id().toString(), item);
        if (item.id().path().endsWith("seeds")) {
            placed.crop = true;
            placed.plant = item;
        }
        blocks.put(key(t), placed);
        return Outcome.OK;
    }

    @Override
    public UseResult use(WorkTarget t, ItemVariant held, int count) {
        Outcome acc = access(t);
        if (!acc.ok()) {
            return new UseResult(acc, held, count, List.of());
        }
        Block b = at(t);
        if (held != null && held.id().path().equals("bone_meal") && b != null && b.crop && !b.mature) {
            b.mature = true;
            return new UseResult(Outcome.OK, held, count - 1, List.of());
        }
        if (held != null && held.id().path().equals("bucket") && b != null && b.source) {
            blocks.remove(key(t));
            ItemVariant filled = ItemVariant.Simple.of(b.fluid.namespace() + ":" + b.fluid.path() + "_bucket");
            return new UseResult(Outcome.OK, held, count - 1, List.of(new Drop(filled, 1)));
        }
        return new UseResult(Outcome.NOTHING_TO_DO, held, count, List.of());
    }

    @Override
    public FluidVariant drainSource(WorkTarget t, boolean simulate) {
        if (!access(t).ok()) {
            return null;
        }
        Block b = at(t);
        if (b == null || b.fluid == null || !b.source) {
            return null;
        }
        if (!simulate) {
            blocks.remove(key(t));
        }
        return new FluidVariant.Simple(b.fluid, "");
    }

    @Override
    public Outcome placeFluid(WorkTarget t, FluidVariant fluid) {
        Outcome acc = access(t);
        if (!acc.ok()) {
            return acc;
        }
        Block b = at(t);
        if (b != null && !b.replaceable) {
            return Outcome.NOT_REPLACEABLE;
        }
        Block f = new Block();
        f.id = fluid.id();
        f.fluid = fluid.id();
        f.source = true;
        blocks.put(key(t), f);
        return Outcome.OK;
    }

    @Override
    public int collectItems(LocalFace face, int range, ItemStore into, int max) {
        List<Drop> ground = groundItems.get(face);
        if (ground == null) {
            return 0;
        }
        int total = 0;
        for (int i = 0; i < ground.size() && total < max; i++) {
            Drop d = ground.get(i);
            int in = into.insert(d.variant(), Math.min(d.count(), max - total), false);
            total += in;
            if (in >= d.count()) {
                ground.remove(i--);
            } else if (in > 0) {
                ground.set(i, new Drop(d.variant(), d.count() - in));
            }
        }
        return total;
    }

    @Override
    public Outcome dropItem(LocalFace face, ItemVariant v, int count) {
        if (policyDisabled) {
            return Outcome.DISABLED_BY_POLICY;
        }
        Block b = at(WorkTarget.adjacent(face));
        if (b != null && !b.replaceable) {
            return Outcome.BLOCKED;
        }
        ejected.add(new Drop(v, count));
        return Outcome.OK;
    }

    @Override
    public int moveEntities(LocalFace face, int range, double strength, boolean pull, boolean includePlayers, int maxEntities) {
        int n = Math.min(maxEntities, entities.getOrDefault(face, 0));
        moved += n;
        return n;
    }

    @Override
    public int countEntities(LocalFace face, int range, String kind) {
        return entities.getOrDefault(face, 0);
    }

    @Override
    public EntitySample sampleMotion(LocalFace face, int range, int maxEntities) {
        int n = Math.min(maxEntities, entities.getOrDefault(face, 0));
        return new EntitySample(n, n * entitySpeed.getOrDefault(face, 0.0));
    }

    @Override
    public void mark(WorkTarget t) {
        marks.add(key(t));
    }

    @Override
    public List<Drop> fishingLoot(LocalFace face, int luck) {
        lastFishingLuck = luck;
        return new ArrayList<>(fishLoot);
    }

    @Override
    public int collectExperience(LocalFace face, int range, int max) {
        int have = xp.getOrDefault(face, 0);
        int take = Math.min(have, max);
        xp.put(face, have - take);
        return take;
    }

    /** Players standing in range of the chassis (every one gets each effect). */
    public int players = 1;
    /** Effects applied, as "effect/ticks/amplifier/range". */
    public final List<String> effects = new ArrayList<>();

    @Override
    public int applyEffect(int range, FfId effect, int ticks, int amplifier) {
        effects.add(effect + "/" + ticks + "/" + amplifier + "/" + range);
        return players;
    }
}
