package dev.fieldframe.core.modules.world;

import dev.fieldframe.api.event.FieldframeEvents;
import dev.fieldframe.api.face.LocalFace;
import dev.fieldframe.api.module.ModuleContext;
import dev.fieldframe.api.resource.FluidVariant;
import dev.fieldframe.api.resource.ItemStore;
import dev.fieldframe.api.resource.ItemVariant;
import dev.fieldframe.api.world.WorkTarget;
import dev.fieldframe.api.world.WorldActions;

import java.util.List;

/**
 * Posts a cancellable {@link FieldframeEvents.WorldAction} before every world-changing action of a world module
 * (including {@code fish} and {@code collect_experience}), so
 * Java addons and KubeJS packs can veto it (a vetoed action reports PROTECTED and changes nothing). Coordinates in the
 * event are face-local: x = depth, y = right, z = up; whole-face actions (entities, items) use 0, 0, 0.
 * Probing is never vetoed.
 */
final class GuardedActions implements WorldActions {
    private final WorldActions inner;
    private final ModuleContext ctx;

    GuardedActions(WorldActions inner, ModuleContext ctx) {
        this.inner = inner;
        this.ctx = ctx;
    }

    private boolean allowed(String action, WorkTarget t) {
        int x = t == null ? 0 : t.depth();
        int y = t == null ? 0 : t.right();
        int z = t == null ? 0 : t.up();
        return ctx.post(new FieldframeEvents.WorldAction(ctx.address(), ctx.definition().id(), action, x, y, z));
    }

    @Override
    public String actorName() {
        return inner.actorName();
    }

    @Override
    public BlockInfo probe(WorkTarget t) {
        return inner.probe(t);
    }

    @Override
    public BreakResult breakBlock(WorkTarget t, ItemVariant tool, boolean requireCorrectTool) {
        return allowed("break", t) ? inner.breakBlock(t, tool, requireCorrectTool) : BreakResult.fail(Outcome.PROTECTED, tool);
    }

    @Override
    public Outcome place(WorkTarget t, ItemVariant item) {
        return allowed("place", t) ? inner.place(t, item) : Outcome.PROTECTED;
    }

    @Override
    public UseResult use(WorkTarget t, ItemVariant held, int count) {
        return allowed("use", t) ? inner.use(t, held, count) : new UseResult(Outcome.PROTECTED, held, count, List.of());
    }

    @Override
    public FluidVariant drainSource(WorkTarget t, boolean simulate) {
        if (simulate) {
            return inner.drainSource(t, true);
        }
        return allowed("drain_fluid", t) ? inner.drainSource(t, false) : null;
    }

    @Override
    public Outcome placeFluid(WorkTarget t, FluidVariant fluid) {
        return allowed("place_fluid", t) ? inner.placeFluid(t, fluid) : Outcome.PROTECTED;
    }

    @Override
    public int collectItems(LocalFace face, int range, ItemStore into, int max) {
        return allowed("collect_items", null) ? inner.collectItems(face, range, into, max) : 0;
    }

    @Override
    public Outcome dropItem(LocalFace face, ItemVariant v, int count) {
        return allowed("drop_item", null) ? inner.dropItem(face, v, count) : Outcome.PROTECTED;
    }

    @Override
    public int moveEntities(LocalFace face, int range, double strength, boolean pull, boolean includePlayers, int maxEntities) {
        return allowed("move_entities", null) ? inner.moveEntities(face, range, strength, pull, includePlayers, maxEntities) : 0;
    }

    @Override
    public List<Drop> fishingLoot(LocalFace face, int luck) {
        return allowed("fish", null) ? inner.fishingLoot(face, luck) : List.of();
    }

    @Override
    public int collectExperience(LocalFace face, int range, int max) {
        return allowed("collect_experience", null) ? inner.collectExperience(face, range, max) : 0;
    }

    @Override
    public int applyEffect(int range, dev.fieldframe.api.id.FfId effect, int ticks, int amplifier) {
        return allowed("apply_effect", null) ? inner.applyEffect(range, effect, ticks, amplifier) : 0;
    }

    @Override
    public int countEntities(LocalFace face, int range, String kind) {
        return inner.countEntities(face, range, kind);
    }

    @Override
    public EntitySample sampleMotion(LocalFace face, int range, int maxEntities) {
        return inner.sampleMotion(face, range, maxEntities);
    }

    @Override
    public void mark(WorkTarget t) {
        inner.mark(t);
    }
}
