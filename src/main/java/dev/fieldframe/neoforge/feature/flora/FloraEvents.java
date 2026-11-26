package dev.fieldframe.neoforge.feature.flora;

import dev.fieldframe.core.flora.Flora;
import dev.fieldframe.core.flora.FloraEffects;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.LargeFireball;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.ClipContext;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.EffectCures;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.event.entity.living.LivingFallEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Applies FLORA effects decided by {@link FloraEffects} on the Forge server. */
public final class FloraEvents {
    private FloraEvents() {
    }

    static final net.minecraft.resources.ResourceLocation FLUX_ID = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("fieldframe", "flora_health_flux");
    private static final String NBT = "fieldframe_flora";
    /** Server thread: a burning-target explosion is under way (its damage must not set off another). */
    private static boolean exploding;

    public static void register() {
        NeoForge.EVENT_BUS.addListener(FloraEvents::playerTick);
        NeoForge.EVENT_BUS.addListener(FloraEvents::hurt);
        NeoForge.EVENT_BUS.addListener(FloraEvents::fall);
    }

    public static float[][] matrix(LivingEntity e) {
        List<Flora.Charge> worn = worn(e);
        return worn.isEmpty() ? null : Flora.matrix(worn);
    }

    /** Charges of the fluid armor pieces worn. */
    public static List<Flora.Charge> worn(LivingEntity e) {
        List<Flora.Charge> worn = new ArrayList<>();
        for (EquipmentSlot slot : new EquipmentSlot[] {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            Flora.Charge c = FluidArmorItem.charge(e.getItemBySlot(slot));
            if (c != null) {
                worn.add(c);
            }
        }
        return worn;
    }

    /**
     * Fluid effects of any fluid (data: fieldframe/fluid_armor_effects), refreshed once a second while worn. Effects are
     * topped up before they run out, so attribute effects (health boost) stay and absorption is granted once.
     */
    static void applyFluidEffects(Player p, long gameTime) {
        if (gameTime % 20 != 0) {
            return;
        }
        var worn = dev.fieldframe.core.flora.FluidArmorEffects.worn(worn(p));
        for (var a : dev.fieldframe.core.flora.FluidArmorEffects.active(worn, dev.fieldframe.neoforge.adapter.McTagLookup.INSTANCE)) {
            if (a.effect().equals(dev.fieldframe.core.flora.FluidArmorEffects.CLEANSE)) {
                for (MobEffectInstance i : List.copyOf(p.getActiveEffects())) {
                    if (i.getEffect().value().getCategory() == net.minecraft.world.effect.MobEffectCategory.HARMFUL) {
                        p.removeEffect(i.getEffect());
                    }
                }
                continue;
            }
            var holder = net.minecraft.core.registries.BuiltInRegistries.MOB_EFFECT.getHolder(
                    net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(a.effect().namespace(), a.effect().path()));
            if (holder.isEmpty()) {
                continue;
            }
            // Night vision flickers below 10 s left, so it gets a longer margin.
            boolean nv = holder.get().is(MobEffects.NIGHT_VISION);
            int duration = nv ? 300 : 100;
            MobEffectInstance cur = p.getEffect(holder.get());
            if (cur == null || cur.getAmplifier() < a.amplifier()
                    || (cur.getAmplifier() == a.amplifier() && !cur.isInfiniteDuration() && cur.getDuration() < (nv ? 220 : 40))) {
                p.addEffect(new MobEffectInstance(holder.get(), duration, a.amplifier(), true, false));
            }
        }
    }

    private static CompoundTag state(Player p) {
        CompoundTag root = p.getPersistentData();
        if (!root.contains(NBT)) {
            root.put(NBT, new CompoundTag());
        }
        return root.getCompound(NBT);
    }

    private static void playerTick(PlayerTickEvent.Post e) {
        if (!(e.getEntity() instanceof Player p && !p.level().isClientSide)) {
            return;
        }
        tickPlayer(p);
    }

    /** One FLORA tick for a player (also used by tests with fake players, which the server does not tick). */
    public static void tickPlayer(Player p) {
        ServerLevel lvl = (ServerLevel) p.level();
        tickPlayer(p, new java.util.Random(p.getRandom().nextLong()), lvl.getGameTime());
    }

    /** Tick with a given random source and game time (tests use a fixed source to make every chance roll pass). */
    public static void tickPlayer(Player p, java.util.random.RandomGenerator rng, long gameTime) {
        CompoundTag st = state(p);
        if (st.getLong("fireCooldown") > 0) {
            st.putLong("fireCooldown", st.getLong("fireCooldown") - 1);
        }
        float[][] m = matrix(p);
        if (m == null) {
            setFlux(p, 0);
            return;
        }
        ServerLevel level = (ServerLevel) p.level();
        boolean cold = level.getBiome(p.blockPosition()).value().coldEnoughToSnow(p.blockPosition());
        for (FloraEffects.Action a : FloraEffects.tick(m, gameTime, cold, rng)) {
            apply(p, level, a);
        }
        applyFluidEffects(p, gameTime);
    }

    static void apply(Player p, ServerLevel level, FloraEffects.Action a) {
        if (a instanceof FloraEffects.ApplyPotion ap) {
            p.addEffect(new MobEffectInstance(effect(ap.potion()), ap.ticks(), ap.amplifier(), true, false));
        } else if (a instanceof FloraEffects.CureEffects) {
            p.removeEffectsCuredBy(EffectCures.MILK);
        } else if (a instanceof FloraEffects.RefillAir) {
            p.setAirSupply(p.getMaxAirSupply());
        } else if (a instanceof FloraEffects.Food f) {
            if (f.delta() > 0) {
                p.getFoodData().eat(f.delta(), f.saturation());
            } else {
                p.getFoodData().setFoodLevel(Math.max(0, p.getFoodData().getFoodLevel() + f.delta()));
            }
        } else if (a instanceof FloraEffects.ColdDamage c) {
            p.hurt(p.damageSources().freeze(), c.amount());
        } else if (a instanceof FloraEffects.Pulse pu) {
            pulse(p, level, pu.kind());
        } else if (a instanceof FloraEffects.Pacify) {
            pacify(p, level);
        } else if (a instanceof FloraEffects.MaxHealthOffset mh) {
            setFlux(p, mh.offset());
        } else if (a instanceof FloraEffects.SelfCombustion sc) {
            level.explode(p, p.getX(), p.getY(), p.getZ(), sc.strength(), Level.ExplosionInteraction.NONE);
        }
    }

    private static Holder<MobEffect> effect(FloraEffects.Potion potion) {
        return switch (potion) {
            case NIGHT_VISION -> MobEffects.NIGHT_VISION;
            case SPEED -> MobEffects.MOVEMENT_SPEED;
            case SLOWNESS -> MobEffects.MOVEMENT_SLOWDOWN;
            case HASTE -> MobEffects.DIG_SPEED;
            case JUMP_BOOST -> MobEffects.JUMP;
        };
    }

    private static void setFlux(Player p, double offset) {
        AttributeInstance attr = p.getAttribute(Attributes.MAX_HEALTH);
        if (attr == null) {
            return;
        }
        AttributeModifier cur = attr.getModifier(FLUX_ID);
        if (offset == 0) {
            if (cur != null) {
                attr.removeModifier(FLUX_ID);
                p.setHealth(Math.min(p.getHealth(), p.getMaxHealth()));
            }
            return;
        }
        attr.removeModifier(FLUX_ID);
        attr.addTransientModifier(new AttributeModifier(FLUX_ID, offset, AttributeModifier.Operation.ADD_VALUE));
        p.setHealth(Math.min(p.getHealth(), p.getMaxHealth()));
    }

    private static List<LivingEntity> near(Player p, ServerLevel level) {
        AABB box = p.getBoundingBox().inflate(FloraEffects.TARGET_RANGE, FloraEffects.TARGET_HEIGHT, FloraEffects.TARGET_RANGE);
        return level.getEntitiesOfClass(LivingEntity.class, box, e -> e != p && e.isAlive());
    }

    /** Pulses hit a random nearby creature instantly, with a particle trail (no projectile entity). */
    private static void pulse(Player p, ServerLevel level, FloraEffects.PulseKind kind) {
        List<LivingEntity> targets = near(p, level);
        if (targets.isEmpty()) {
            return;
        }
        LivingEntity t = targets.get(p.getRandom().nextInt(targets.size()));
        Vec3 from = p.getEyePosition();
        Vec3 to = t.position().add(0, t.getBbHeight() / 2, 0);
        for (int i = 0; i <= 8; i++) {
            Vec3 v = from.lerp(to, i / 8.0);
            level.sendParticles(ParticleTypes.END_ROD, v.x, v.y, v.z, 1, 0, 0, 0, 0);
        }
        switch (kind) {
            case DAMAGE -> t.hurt(p.damageSources().indirectMagic(p, p), 3f);
            case IGNITE -> t.igniteForSeconds(10);
            case SLOW -> t.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 200, 3));
            case ANTIDOTE -> t.removeEffectsCuredBy(EffectCures.MILK);
            case TELEPORT -> move(t, t.getX() + p.getRandom().nextDouble() * 10 - 5, t.getY() + 5, t.getZ() + p.getRandom().nextDouble() * 10 - 5);
        }
    }

    private static void pacify(Player p, ServerLevel level) {
        List<LivingEntity> near = near(p, level);
        if (near.size() < 2) {
            return;
        }
        LivingEntity a = near.get(p.getRandom().nextInt(near.size()));
        LivingEntity b = near.get(p.getRandom().nextInt(near.size()));
        if (a != b && a instanceof Mob mob) {
            mob.setLastHurtByMob(b);
            mob.setTarget(b);
        }
    }

    private static void hurt(LivingIncomingDamageEvent e) {
        if (e.getEntity().level().isClientSide) {
            return;
        }
        if (e.getSource().getEntity() instanceof Player attacker && attacker != e.getEntity()) {
            float[][] m = matrix(attacker);
            if (m != null) {
                FloraEffects.AttackResult r = FloraEffects.onWearerAttack(m);
                LivingEntity target = e.getEntity();
                // Only a real hit sets it off: the explosion's own damage also counts as the wearer's and the target
                // still burns, so without both checks it would set off another explosion, recursing until the stack ran out.
                if (r.explosionIfBurning() > 0 && target.isOnFire() && !exploding && !e.getSource().is(DamageTypeTags.IS_EXPLOSION)) {
                    exploding = true;
                    try {
                        target.level().explode(attacker, target.getX(), target.getY(), target.getZ(), r.explosionIfBurning(), Level.ExplosionInteraction.NONE);
                    } finally {
                        exploding = false;
                    }
                }
                if (r.igniteSeconds() > 0) {
                    target.igniteForSeconds(r.igniteSeconds());
                }
            }
        }
        if (e.getEntity() instanceof Player p && !p.level().isClientSide) {
            float[][] m = matrix(p);
            if (m == null) {
                return;
            }
            CompoundTag st = state(p);
            boolean fromMob = e.getSource().getEntity() instanceof Mob;
            FloraEffects.HurtResult r = FloraEffects.onWearerHurt(m, e.getAmount(), p.getHealth(), fromMob,
                    e.getSource().is(DamageTypeTags.IS_FIRE), st.getLong("fireCooldown"));
            if (!r.cancel()) {
                return;
            }
            e.setAmount(0);
            e.setCanceled(true);
            if (r.fireImmunityTicksAdded() > 0) {
                st.putLong("fireCooldown", Math.max(40, st.getLong("fireCooldown") + r.fireImmunityTicksAdded()));
            }
            if (r.teleportSelfRange() > 0) {
                randomSurface(p, p, r.teleportSelfRange());
            }
            if (r.teleportAttackerRange() > 0 && e.getSource().getEntity() instanceof LivingEntity att) {
                randomSurface(p, att, r.teleportAttackerRange());
            }
        }
    }

    /** Teleports {@code who} to the surface at a random spot within {@code range} of {@code around}. */
    private static void randomSurface(Player around, LivingEntity who, int range) {
        ServerLevel level = (ServerLevel) around.level();
        int x = (int) around.getX() + around.getRandom().nextInt(2 * range + 1) - range;
        int z = (int) around.getZ() + around.getRandom().nextInt(2 * range + 1) - range;
        BlockPos col = new BlockPos(x, 0, z);
        if (!level.isLoaded(col)) {
            return;
        }
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        move(who, x + 0.5, y, z + 0.5);
    }

    private static void fall(LivingFallEvent e) {
        if (e.getEntity() instanceof Player p && !p.level().isClientSide) {
            float[][] m = matrix(p);
            if (m != null) {
                e.setDistance(FloraEffects.onWearerFall(m, e.getDistance()));
            }
        }
    }

    /** Teleports an entity; fake players (whose network handler ignores teleports) are moved directly. */
    private static void move(LivingEntity e, double x, double y, double z) {
        if (e instanceof net.neoforged.neoforge.common.util.FakePlayer) {
            e.setPos(x, y, z);
        } else {
            e.teleportTo(x, y, z);
        }
    }

    // ------------------------------------------------------------------ key actions (server side)

    public static final int KEY_BLINK_OR_SINK = 0;
    public static final int KEY_FIREBALL = 1;

    public static void keyAction(ServerPlayer p, int action) {
        float[][] m = matrix(p);
        if (m == null) {
            return;
        }
        ServerLevel level = (ServerLevel) p.level();
        if (action == KEY_BLINK_OR_SINK) {
            if (p.isShiftKeyDown()) {
                int depth = FloraEffects.sinkDepth(m);
                // Original moved the player into the ground; here: first free 2-block space below the floor.
                for (int i = 2; i <= depth; i++) {
                    BlockPos feet = p.blockPosition().below(i);
                    if (!level.isLoaded(feet)) {
                        return;
                    }
                    if (level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
                            && level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty()
                            && !level.getBlockState(feet.below()).getCollisionShape(level, feet.below()).isEmpty()) {
                        move(p, p.getX(), feet.getY(), p.getZ());
                        return;
                    }
                }
            } else {
                int range = FloraEffects.blinkRange(m);
                if (range <= 0) {
                    return;
                }
                Vec3 eye = p.getEyePosition();
                Vec3 end = eye.add(p.getLookAngle().scale(range));
                BlockHitResult hit = level.clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
                if (hit.getType() == HitResult.Type.BLOCK) {
                    BlockPos top = hit.getBlockPos().above();
                    move(p, top.getX() + 0.5, top.getY(), top.getZ() + 0.5);
                }
            }
        } else if (action == KEY_FIREBALL) {
            int cooldown = FloraEffects.fireballCooldown(m);
            CompoundTag st = state(p);
            if (cooldown < 0 || st.getLong("fireballReady") > level.getGameTime()) {
                return;
            }
            st.putLong("fireballReady", level.getGameTime() + cooldown);
            Vec3 look = p.getLookAngle();
            LargeFireball fb = new LargeFireball(level, p, look, 1);
            fb.setPos(p.getX() + look.x * 4, p.getY(0.5) + 0.5, p.getZ() + look.z * 4);
            level.addFreshEntity(fb);
        }
    }
}
