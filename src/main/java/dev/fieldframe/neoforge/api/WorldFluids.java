package dev.fieldframe.neoforge.api;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.InsideBlockEffectApplier;
import net.minecraft.world.entity.InsideBlockEffectType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.neoforge.common.SoundActions;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.fluids.BaseFlowingFluid;
import net.neoforged.neoforge.fluids.FluidType;

/**
 * Public API: fluids that can be poured into the world. A fluid at or above {@link #HOT_KELVIN} behaves like lava
 * (slow, short spread, sets entities and nearby flammables on fire); anything cooler behaves like water.
 * Built-in fluids and addons use the same helpers.
 */
public final class WorldFluids {
    private WorldFluids() {
    }

    /** Temperature (kelvin) from which a fluid behaves like lava. Lava itself is 1300. */
    public static final int HOT_KELVIN = 1000;

    public static boolean hot(int temperature) {
        return temperature >= HOT_KELVIN;
    }

    /** Bucket sounds matching water or lava. */
    public static FluidType.Properties sounds(FluidType.Properties p, boolean hot) {
        return p.sound(SoundActions.BUCKET_FILL, hot ? SoundEvents.BUCKET_FILL_LAVA : SoundEvents.BUCKET_FILL)
                .sound(SoundActions.BUCKET_EMPTY, hot ? SoundEvents.BUCKET_EMPTY_LAVA : SoundEvents.BUCKET_EMPTY);
    }

    /** Flow speed and spread: lava's (every 30 ticks, 2 steps, 4 blocks) or water's (every 5 ticks, 4 steps, 7 blocks). */
    public static BaseFlowingFluid.Properties flow(BaseFlowingFluid.Properties p, boolean hot) {
        return hot ? p.tickRate(30).slopeFindDistance(2).levelDecreasePerBlock(2).explosionResistance(100f)
                : p.tickRate(5).slopeFindDistance(4).levelDecreasePerBlock(1).explosionResistance(100f);
    }

    /** Block properties of a placed fluid: vanilla water's or lava's, with the given light level. */
    public static BlockBehaviour.Properties block(BlockBehaviour.Properties p, boolean hot, int light) {
        p.mapColor(hot ? MapColor.FIRE : MapColor.WATER).replaceable().noCollision().strength(100f)
                .pushReaction(PushReaction.POPPED).noLootTable().liquid().sound(SoundType.EMPTY).lightLevel(s -> light);
        return hot ? p.randomTicks() : p;
    }

    public static BaseFlowingFluid.Source source(BaseFlowingFluid.Properties p, boolean hot) {
        return hot ? new HotSource(p) : new BaseFlowingFluid.Source(p);
    }

    public static BaseFlowingFluid.Flowing flowing(BaseFlowingFluid.Properties p, boolean hot) {
        return hot ? new HotFlowing(p) : new BaseFlowingFluid.Flowing(p);
    }

    /** Hot source block: burns what's in it and lights flammable blocks nearby, like lava. */
    public static class HotSource extends BaseFlowingFluid.Source {
        public HotSource(Properties p) {
            super(p);
        }

        @Override
        protected boolean isRandomlyTicking() {
            return true;
        }

        @Override
        protected void randomTick(ServerLevel level, BlockPos pos, FluidState state, RandomSource random) {
            spreadFire(level, pos, random);
        }

        @Override
        protected void entityInside(Level level, BlockPos pos, Entity entity, InsideBlockEffectApplier effects) {
            burn(effects);
        }
    }

    /** Hot flowing fluid, see {@link HotSource}. */
    public static class HotFlowing extends BaseFlowingFluid.Flowing {
        public HotFlowing(Properties p) {
            super(p);
        }

        @Override
        protected boolean isRandomlyTicking() {
            return true;
        }

        @Override
        protected void randomTick(ServerLevel level, BlockPos pos, FluidState state, RandomSource random) {
            spreadFire(level, pos, random);
        }

        @Override
        protected void entityInside(Level level, BlockPos pos, Entity entity, InsideBlockEffectApplier effects) {
            burn(effects);
        }
    }

    private static void burn(InsideBlockEffectApplier effects) {
        effects.apply(InsideBlockEffectType.CLEAR_FREEZE);
        effects.apply(InsideBlockEffectType.LAVA_IGNITE);
        effects.runAfter(InsideBlockEffectType.LAVA_IGNITE, Entity::lavaHurt);
    }

    /** Lava's fire spread: a few tries at air just above that touches something flammable. */
    static void spreadFire(ServerLevel level, BlockPos pos, RandomSource random) {
        if (!level.canSpreadFireAround(pos)) {
            return;
        }
        for (int i = 0; i < 3; i++) {
            BlockPos at = pos.offset(random.nextInt(3) - 1, 1, random.nextInt(3) - 1);
            if (!level.isLoaded(at) || !level.isEmptyBlock(at)) {
                continue;
            }
            for (Direction d : Direction.values()) {
                BlockPos n = at.relative(d);
                if (level.isLoaded(n) && level.getBlockState(n).isFlammable(level, n, d.getOpposite())) {
                    BlockState fire = EventHooks.fireFluidPlaceBlockEvent(level, at, pos, BaseFireBlock.getState(level, at));
                    level.setBlockAndUpdate(at, fire);
                    return;
                }
            }
        }
    }
}
