package dev.fieldframe.forge.chassis;

import dev.fieldframe.api.face.LocalFace;
import dev.fieldframe.api.world.WorldPos;
import dev.fieldframe.core.chassis.InstalledModule;
import dev.fieldframe.core.modules.power.BurstPower;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

/**
 * Lets Blast Dampers catch explosions: on an explosion's start, the nearest damper whose radius covers it (from
 * {@link BurstPower#registry()}) cancels it and stores its energy on its next tick. Nothing breaks and nobody is hurt.
 */
public final class BlastEvents {
    private BlastEvents() {
    }

    public static void register() {
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(BlastEvents::explosion);
    }

    private static void explosion(net.minecraftforge.event.level.ExplosionEvent.Start e) {
        if (!(e.getLevel() instanceof ServerLevel level)) {
            return;
        }
        net.minecraft.world.phys.Vec3 c = e.getExplosion().getPosition();
        WorldPos at = BurstPower.registry().nearest(level.dimension().location().toString(), c.x, c.y, c.z, level.getGameTime(), 40);
        if (at == null) {
            return;
        }
        BlockPos pos = new BlockPos(at.x(), at.y(), at.z());
        if (!level.isLoaded(pos) || !(level.getBlockEntity(pos) instanceof ChassisBlockEntity be)) {
            return;
        }
        for (LocalFace f : LocalFace.ALL) {
            InstalledModule m = be.core().module(f);
            if (m != null && m.behavior() instanceof BurstPower.BlastDamper damper) {
                float power = radius(e.getExplosion());
                damper.queue(power);
                e.setCanceled(true);
                level.playSound(null, c.x, c.y, c.z, SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS, 1.0f, 0.6f);
                level.sendParticles(ParticleTypes.POOF, c.x, c.y, c.z, 20, 0.6, 0.6, 0.6, 0.02);
                return;
            }
        }
    }

    /** Explosion.radius is private on 1.20.1: named in development, SRG name in production. */
    private static float radius(net.minecraft.world.level.Explosion explosion) {
        for (String name : new String[]{"radius", "f_46017_"}) {
            try {
                java.lang.reflect.Field f = net.minecraft.world.level.Explosion.class.getDeclaredField(name);
                f.setAccessible(true);
                return f.getFloat(explosion);
            } catch (ReflectiveOperationException ignored) {
                // try the other name
            }
        }
        return BurstPower.TNT_POWER;
    }
}
