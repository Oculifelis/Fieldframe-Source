package dev.fieldframe.neoforge.test;

import com.mojang.authlib.GameProfile;
import dev.fieldframe.api.FieldframeRegistry;
import dev.fieldframe.api.data.FfData;
import dev.fieldframe.api.face.LocalFace;
import dev.fieldframe.api.face.WorldDir;
import dev.fieldframe.core.chassis.ChassisCore;
import dev.fieldframe.core.endertech.EnderTechRules;
import dev.fieldframe.core.endertech.TankStructure;
import dev.fieldframe.core.flora.Flora;
import dev.fieldframe.core.modules.BuiltinModules;
import dev.fieldframe.core.util.PerfProbe;
import dev.fieldframe.neoforge.Fieldframe;
import dev.fieldframe.neoforge.adapter.McFluidVariant;
import dev.fieldframe.neoforge.adapter.McItemVariant;
import dev.fieldframe.neoforge.adapter.NbtBridge;
import dev.fieldframe.neoforge.chassis.ChassisBlock;
import dev.fieldframe.neoforge.chassis.ChassisBlockEntity;
import dev.fieldframe.neoforge.feature.FeatureContent;
import dev.fieldframe.neoforge.feature.ItemEnergy;
import dev.fieldframe.neoforge.feature.endertech.ExchangerItem;
import dev.fieldframe.neoforge.feature.endertech.ExchangerService;
import dev.fieldframe.neoforge.feature.endertech.TankControllerEntity;
import dev.fieldframe.neoforge.feature.flora.FloraEvents;
import dev.fieldframe.neoforge.registry.FfContent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.hurtingprojectile.LargeFireball;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.common.CommonHooks;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.level.block.BreakBlockEvent;
import net.neoforged.neoforge.fluids.FluidStack;

import java.io.ByteArrayOutputStream;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.random.RandomGenerator;

/**
 * Closes verification gaps: FLORA effects, pulses and key actions in-world, Exchanger protection/creative/radius
 * keys, the Ender Tank controller keeping contents in its item, the untested world modules, and an in-game
 * performance sample.
 */
public final class VerificationGameTests {
    private static final String T = "empty7";

    public static void portableFuelUsesCookingComponents(GameTestHelper helper) {
        var pos = helper.absolutePos(new BlockPos(3, 3, 3));
        helper.assertTrue(helper.getLevel().getBlockEntity(pos) == null, "portable context has no physical block entity");
        var world = new dev.fieldframe.neoforge.chassis.ForgeChassisWorld(helper.getLevel(), pos,
                new dev.fieldframe.api.face.Orientation(WorldDir.NORTH));
        helper.assertTrue(world.fuelBurnTicks(McItemVariant.of(new ItemStack(Items.COAL))) == 1600, "portable coal burn time");
        helper.assertTrue(world.fuelBurnTicks(McItemVariant.of(new ItemStack(Items.STICK))) == 100, "portable stick burn time");
        helper.succeed();
    }
    private static final Set<BlockPos> PROTECTED = ConcurrentHashMap.newKeySet();

    static {
        NeoForge.EVENT_BUS.addListener((BreakBlockEvent e) -> {
            if (PROTECTED.contains(e.getPos())) {
                e.setCanceled(true);
            }
        });
    }

    /** Every chance roll passes (nextInt(n) = 0), gaussian = 1. */
    static final RandomGenerator ALWAYS = new RandomGenerator() {
        @Override
        public long nextLong() {
            return 0;
        }

        @Override
        public int nextInt(int bound) {
            return 0;
        }

        @Override
        public double nextGaussian() {
            return 1.0;
        }
    };

    private static ItemStack armor(String path, Object... fluidAmounts) {
        ItemStack s = new ItemStack(FeatureContent.ARMOR.get(path).get());
        Flora.Charge c = new Flora.Charge();
        for (int i = 0; i < fluidAmounts.length; i += 2) {
            c.fill((Flora.Fluid) fluidAmounts[i], ((Number) fluidAmounts[i + 1]).longValue(), Flora.Tier.ENDER_ALLOY.capacity, false);
        }
        dev.fieldframe.neoforge.feature.ItemEnergy.editData(s, t -> t.put(Flora.Charge.DATA_KEY, NbtBridge.toTag(c.save())));
        return s;
    }

    private static FakePlayer fake(GameTestHelper h, BlockPos rel) {
        FakePlayer fp = FakePlayerFactory.get(h.getLevel(), new GameProfile(UUID.randomUUID(), "[ff-verify]"));
        BlockPos a = h.absolutePos(rel);
        fp.setPos(a.getX() + 0.5, a.getY(), a.getZ() + 0.5);
        return fp;
    }

    private static Player mock(GameTestHelper h, BlockPos rel) {
        Player p = h.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
        BlockPos a = h.absolutePos(rel);
        p.setPos(a.getX() + 0.5, a.getY(), a.getZ() + 0.5);
        return p;
    }

    // ------------------------------------------------------------------ FLORA: per-tick effects

    public static void floraPotionEffects(GameTestHelper h) {
        FakePlayer fp = fake(h, new BlockPos(3, 1, 3));
        fp.setItemSlot(EquipmentSlot.CHEST, armor("ender_alloy_fluid_chestplate", Flora.Fluid.GLOWSTONE, 1000));
        FloraEvents.tickPlayer(fp, ALWAYS, 1);
        h.assertTrue(fp.hasEffect(MobEffects.NIGHT_VISION), "Glowstone-Glowstone night vision");
        // Arcane-Arcane (antidote) would clear it again in the same tick, so the other pairs are checked separately.
        fp.setItemSlot(EquipmentSlot.CHEST, ItemStack.EMPTY);
        fp.setItemSlot(EquipmentSlot.LEGS, armor("ender_alloy_fluid_leggings", Flora.Fluid.ARCANE, 1000, Flora.Fluid.ENDER, 1000));
        fp.setItemSlot(EquipmentSlot.FEET, armor("ender_alloy_fluid_boots", Flora.Fluid.BLAZING, 1000, Flora.Fluid.GELID, 1000, Flora.Fluid.COAL, 1000));
        FloraEvents.tickPlayer(fp, ALWAYS, 1);
        h.assertTrue(fp.hasEffect(MobEffects.JUMP_BOOST), "Arcane-Ender long jump");
        h.assertTrue(fp.hasEffect(MobEffects.SLOWNESS), "Blazing-Gelid slowness");
        h.assertTrue(fp.hasEffect(MobEffects.HASTE), "Coal-Gelid haste");
        h.assertTrue(fp.hasEffect(MobEffects.SPEED), "Blazing-Arcane speed");
        h.succeed();
    }

    public static void floraAirAntidoteAndFood(GameTestHelper h) {
        FakePlayer fp = fake(h, new BlockPos(3, 1, 3));
        fp.setItemSlot(EquipmentSlot.CHEST, armor("ender_alloy_fluid_chestplate", Flora.Fluid.GELID, 1000, Flora.Fluid.ARCANE, 1000));
        fp.setAirSupply(10);
        fp.addEffect(new MobEffectInstance(MobEffects.POISON, 200));
        fp.getFoodData().setFoodLevel(10);
        FloraEvents.tickPlayer(fp, ALWAYS, 1);
        h.assertTrue(fp.getAirSupply() == fp.getMaxAirSupply(), "Gelid-Gelid refills air, air " + fp.getAirSupply());
        h.assertFalse(fp.hasEffect(MobEffects.POISON), "Arcane-Arcane antidote");
        h.assertTrue(fp.getFoodData().getFoodLevel() == 9, "Gelid-Arcane hunger loss, food " + fp.getFoodData().getFoodLevel());
        fp.setItemSlot(EquipmentSlot.CHEST, armor("ender_alloy_fluid_chestplate", Flora.Fluid.ARCANE, 1000, Flora.Fluid.GLOWSTONE, 1000));
        fp.getFoodData().setFoodLevel(10);
        FloraEvents.tickPlayer(fp, ALWAYS, 1);
        h.assertTrue(fp.getFoodData().getFoodLevel() == 14, "Arcane-Glowstone hunger gain, food " + fp.getFoodData().getFoodLevel());
        h.succeed();
    }

    public static void floraMaxHealthFluxIsRemovable(GameTestHelper h) {
        FakePlayer fp = fake(h, new BlockPos(3, 1, 3));
        double base = fp.getAttributeValue(Attributes.MAX_HEALTH);
        fp.setItemSlot(EquipmentSlot.CHEST, armor("ender_alloy_fluid_chestplate", Flora.Fluid.REDSTONE, 100_000));
        FloraEvents.tickPlayer(fp, ALWAYS, 600);
        double flux = fp.getAttributeValue(Attributes.MAX_HEALTH);
        h.assertTrue(flux != base, "Redstone-Redstone changes max health, " + base + " -> " + flux);
        fp.setItemSlot(EquipmentSlot.CHEST, ItemStack.EMPTY);
        FloraEvents.tickPlayer(fp, ALWAYS, 601);
        h.assertTrue(fp.getAttributeValue(Attributes.MAX_HEALTH) == base, "modifier removed when the armor comes off");
        h.succeed();
    }

    public static void floraPulsesHitNearbyCreature(GameTestHelper h) {
        FakePlayer fp = fake(h, new BlockPos(1, 1, 3));
        Cow cow = h.spawn(net.minecraft.world.entity.EntityTypes.COW, new BlockPos(5, 1, 3));
        cow.setNoAi(true);
        double z0 = cow.getZ();
        float hp0 = cow.getHealth();
        fp.setItemSlot(EquipmentSlot.CHEST, armor("ender_alloy_fluid_chestplate", Flora.Fluid.REDSTONE, 1000, Flora.Fluid.COAL, 1000,
                Flora.Fluid.BLAZING, 1000, Flora.Fluid.GELID, 1000, Flora.Fluid.ENDER, 1000));
        // A pulse hits a random creature within range: leftovers from earlier tests would draw it away from the cow.
        net.minecraft.world.phys.AABB range = fp.getBoundingBox().inflate(dev.fieldframe.core.flora.FloraEffects.TARGET_RANGE,
                dev.fieldframe.core.flora.FloraEffects.TARGET_HEIGHT, dev.fieldframe.core.flora.FloraEffects.TARGET_RANGE);
        for (net.minecraft.world.entity.LivingEntity other : h.getLevel().getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,
                range, e -> e != cow && !(e instanceof net.minecraft.world.entity.player.Player))) {
            other.discard();
        }
        FloraEvents.tickPlayer(fp, ALWAYS, 1);
        h.assertTrue(cow.getHealth() < hp0, "damaging pulse");
        h.assertTrue(cow.isOnFire(), "igniting pulse");
        h.assertTrue(cow.hasEffect(MobEffects.SLOWNESS), "slowness pulse");
        h.assertTrue(cow.getZ() != z0 || cow.getY() > h.absolutePos(new BlockPos(5, 1, 3)).getY(), "teleport pulse moved the cow");
        h.succeed();
    }

    public static void floraSelfCombustionBreaksNoBlocks(GameTestHelper h) {
        for (int x = 1; x <= 5; x++) {
            h.setBlock(new BlockPos(x, 1, 2), Blocks.STONE);
        }
        FakePlayer fp = fake(h, new BlockPos(3, 1, 3));
        fp.setItemSlot(EquipmentSlot.CHEST, armor("ender_alloy_fluid_chestplate", Flora.Fluid.REDSTONE, 100_000, Flora.Fluid.GLOWSTONE, 100_000));
        FloraEvents.tickPlayer(fp, ALWAYS, 1);
        for (int x = 1; x <= 5; x++) {
            h.assertBlockPresent(Blocks.STONE, new BlockPos(x, 1, 2));
        }
        h.succeed();
    }

    // ------------------------------------------------------------------ FLORA: damage, attack and fall events

    public static void floraEscapeTeleportWhenBadlyHurt(GameTestHelper h) {
        Player p = mock(h, new BlockPos(3, 1, 3));
        p.setItemSlot(EquipmentSlot.CHEST, armor("ender_alloy_fluid_chestplate", Flora.Fluid.GELID, 1000, Flora.Fluid.ENDER, 1000));
        double x0 = p.getX(), z0 = p.getZ(), y0 = p.getY();
        p.hurtServer(h.getLevel(), h.getLevel().damageSources().generic(), 18f);
        h.assertTrue(p.getHealth() == p.getMaxHealth(), "lethal-ish damage cancelled, health " + p.getHealth());
        h.assertTrue(p.getX() != x0 || p.getZ() != z0 || p.getY() != y0, "player teleported away");
        h.succeed();
    }

    public static void floraFireProtectionAndAttackerTeleport(GameTestHelper h) {
        Player p = mock(h, new BlockPos(3, 1, 3));
        p.setItemSlot(EquipmentSlot.CHEST, armor("ender_alloy_fluid_chestplate", Flora.Fluid.BLAZING, 1000, Flora.Fluid.GLOWSTONE, 1000));
        p.hurtServer(h.getLevel(), h.getLevel().damageSources().onFire(), 4f);
        h.assertTrue(p.getHealth() == p.getMaxHealth(), "Blazing-Glowstone fire protection, health " + p.getHealth());
        Player q = mock(h, new BlockPos(3, 1, 3));
        q.setItemSlot(EquipmentSlot.CHEST, armor("ender_alloy_fluid_chestplate", Flora.Fluid.COAL, 1000, Flora.Fluid.ENDER, 1000));
        Zombie z = h.spawn(net.minecraft.world.entity.EntityTypes.ZOMBIE, new BlockPos(4, 1, 3));
        z.setNoAi(true);
        BlockPos zp = z.blockPosition();
        float before = q.getHealth();
        boolean hurt = q.hurtServer(h.getLevel(), h.getLevel().damageSources().mobAttack(z), 3f);
        h.assertTrue(!hurt && q.getHealth() == before, "Coal-Ender cancels mob damage (hurt=" + hurt + ", health " + before + " -> " + q.getHealth()
                + ", last " + q.getLastDamageSource() + ")");
        h.assertTrue(!z.blockPosition().equals(zp), "attacker teleported");
        h.succeed();
    }

    /** Gelid-Glowstone: frost bites the wearer in a cold biome only (the test paints its own snowy patch, then restores it). */
    public static void floraColdDamageOnlyInTheCold(GameTestHelper h) {
        Player p = mock(h, new BlockPos(3, 1, 3));
        p.setItemSlot(EquipmentSlot.CHEST, armor("ender_alloy_fluid_chestplate", Flora.Fluid.GELID, 1000, Flora.Fluid.GLOWSTONE, 1000));
        net.minecraft.server.level.ServerLevel level = h.getLevel();
        BlockPos at = p.blockPosition();
        BlockPos from = at.offset(-2, -2, -2);
        BlockPos to = at.offset(2, 2, 2);
        net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> original = level.getBiome(at);
        var biomes = level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.BIOME);
        try {
            net.minecraft.server.commands.FillBiomeCommand.fill(level, from, to, biomes.getOrThrow(net.minecraft.world.level.biome.Biomes.DESERT));
            float hp = p.getHealth();
            FloraEvents.tickPlayer(p, ALWAYS, 1);
            h.assertTrue(p.getHealth() == hp, "no frost in a desert");
            net.minecraft.server.commands.FillBiomeCommand.fill(level, from, to, biomes.getOrThrow(net.minecraft.world.level.biome.Biomes.SNOWY_PLAINS));
            FloraEvents.tickPlayer(p, ALWAYS, 2);
            h.assertTrue(p.getHealth() < hp, "frost hurts in snowy plains");
            h.assertTrue(p.getLastDamageSource() != null && p.getLastDamageSource().is(net.minecraft.world.damagesource.DamageTypes.FREEZE),
                    "freeze damage, got " + p.getLastDamageSource());
        } finally {
            net.minecraft.server.commands.FillBiomeCommand.fill(level, from, to, original);
        }
        h.succeed();
    }

    /** Coal-Arcane: nearby mobs turn on each other (one targets the other), never on the wearer. */
    public static void floraPacifyTurnsMobsOnEachOther(GameTestHelper h) {
        Player p = mock(h, new BlockPos(1, 1, 1));
        net.minecraft.world.entity.monster.zombie.Zombie zombie = h.spawn(net.minecraft.world.entity.EntityTypes.ZOMBIE, new BlockPos(5, 1, 5));
        Cow cow = h.spawn(net.minecraft.world.entity.EntityTypes.COW, new BlockPos(5, 1, 3));
        zombie.setNoAi(true);
        cow.setNoAi(true);
        net.minecraft.world.phys.AABB range = p.getBoundingBox().inflate(dev.fieldframe.core.flora.FloraEffects.TARGET_RANGE,
                dev.fieldframe.core.flora.FloraEffects.TARGET_HEIGHT, dev.fieldframe.core.flora.FloraEffects.TARGET_RANGE);
        for (net.minecraft.world.entity.LivingEntity other : h.getLevel().getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,
                range, e -> e != cow && e != zombie && !(e instanceof Player))) {
            other.discard();
        }
        p.setItemSlot(EquipmentSlot.CHEST, armor("ender_alloy_fluid_chestplate", Flora.Fluid.COAL, 1000, Flora.Fluid.ARCANE, 1000));
        // Pacify picks two random creatures; with two in range it pairs them within a few tries.
        for (int i = 0; i < 64 && zombie.getTarget() != cow && cow.getTarget() != zombie; i++) {
            FloraEvents.tickPlayer(p, ALWAYS, 10 + i);
        }
        h.assertTrue(zombie.getTarget() == cow || cow.getTarget() == zombie, "the zombie and the cow turned on each other");
        h.assertTrue(zombie.getTarget() != p && cow.getTarget() != p, "never on the wearer");
        h.succeed();
    }

    /** Coal-Blazing: hitting a burning creature sets off a small explosion that hurts what stands by; an unlit one does not. */
    public static void floraBurningTargetExplodes(GameTestHelper h) {
        Player p = mock(h, new BlockPos(1, 1, 1));
        p.setItemSlot(EquipmentSlot.CHEST, armor("ender_alloy_fluid_chestplate", Flora.Fluid.COAL, 1000, Flora.Fluid.BLAZING, 1000));
        Cow cold = h.spawn(net.minecraft.world.entity.EntityTypes.COW, new BlockPos(1, 1, 5));
        Cow coldBystander = h.spawn(net.minecraft.world.entity.EntityTypes.COW, new BlockPos(2, 1, 5));
        Cow burning = h.spawn(net.minecraft.world.entity.EntityTypes.COW, new BlockPos(6, 1, 5));
        Cow bystander = h.spawn(net.minecraft.world.entity.EntityTypes.COW, new BlockPos(7, 1, 5));
        for (Cow c : new Cow[]{cold, coldBystander, burning, bystander}) {
            c.setNoAi(true);
        }
        cold.hurtServer(h.getLevel(), h.getLevel().damageSources().playerAttack(p), 1f);
        h.assertTrue(coldBystander.getHealth() == coldBystander.getMaxHealth(), "no explosion at an unlit target");
        burning.igniteForSeconds(5);
        burning.hurtServer(h.getLevel(), h.getLevel().damageSources().playerAttack(p), 1f);
        h.assertTrue(bystander.getHealth() < bystander.getMaxHealth(), "the burning target exploded and hurt its neighbour");
        h.assertTrue(h.getBlockState(new BlockPos(6, 0, 5)).equals(h.getBlockState(new BlockPos(1, 0, 5))), "no block damage");
        h.succeed();
    }

    public static void floraAttackIgnitesAndFallRules(GameTestHelper h) {
        Player p = mock(h, new BlockPos(3, 1, 3));
        p.setItemSlot(EquipmentSlot.CHEST, armor("ender_alloy_fluid_chestplate", Flora.Fluid.COAL, 1000, Flora.Fluid.GLOWSTONE, 1000));
        Cow cow = h.spawn(net.minecraft.world.entity.EntityTypes.COW, new BlockPos(4, 1, 3));
        cow.setNoAi(true);
        cow.hurtServer(h.getLevel(), h.getLevel().damageSources().playerAttack(p), 1f);
        h.assertTrue(cow.isOnFire(), "Coal-Glowstone ignites the target");
        p.setItemSlot(EquipmentSlot.CHEST, armor("ender_alloy_fluid_chestplate", Flora.Fluid.BLAZING, 1000));
        var up = CommonHooks.onLivingFall(p, 10f, 1f);
        h.assertTrue(up.getDistance() > 10f, "Blazing-Blazing increases fall distance");
        p.setItemSlot(EquipmentSlot.CHEST, armor("ender_alloy_fluid_chestplate", Flora.Fluid.BLAZING, 1000, Flora.Fluid.ENDER, 1000));
        var down = CommonHooks.onLivingFall(p, 10f, 1f);
        h.assertTrue(down.getDistance() < 10f, "Blazing-Ender reduces fall distance, got " + down.getDistance());
        h.succeed();
    }

    // ------------------------------------------------------------------ FLORA: key actions

    public static void floraKeyActions(GameTestHelper h) {
        // Blink: look at a pillar 4 blocks east at eye height; land on top of the block hit.
        for (int y = 0; y <= 3; y++) {
            h.setBlock(new BlockPos(5, y, 3), Blocks.STONE);
        }
        h.setBlock(new BlockPos(1, 0, 3), Blocks.STONE);
        FakePlayer fp = fake(h, new BlockPos(1, 1, 3));
        fp.setYRot(-90); // east
        fp.setXRot(0);
        fp.setItemSlot(EquipmentSlot.CHEST, armor("ender_alloy_fluid_chestplate", Flora.Fluid.ENDER, 1000));
        double x0 = fp.getX();
        FloraEvents.keyAction(fp, FloraEvents.KEY_BLINK_OR_SINK);
        h.assertTrue(fp.getX() > x0 + 2, "blink moved the player east, x " + x0 + " -> " + fp.getX());
        // Sink: standing on a floor with a 2-block gap and solid ground below.
        h.setBlock(new BlockPos(3, 3, 1), Blocks.STONE);
        h.setBlock(new BlockPos(3, 0, 1), Blocks.STONE);
        FakePlayer sink = fake(h, new BlockPos(3, 4, 1));
        sink.setShiftKeyDown(true);
        sink.setItemSlot(EquipmentSlot.CHEST, armor("ender_alloy_fluid_chestplate", Flora.Fluid.ENDER, 1000, Flora.Fluid.GLOWSTONE, 1000));
        FloraEvents.keyAction(sink, FloraEvents.KEY_BLINK_OR_SINK);
        h.assertTrue((int) Math.floor(sink.getY()) == h.absolutePos(new BlockPos(3, 1, 1)).getY(), "sank to the free space, y " + sink.getY());
        // Fireball: Coal-Coal, with cooldown.
        FakePlayer fire = fake(h, new BlockPos(3, 1, 5));
        fire.setItemSlot(EquipmentSlot.CHEST, armor("ender_alloy_fluid_chestplate", Flora.Fluid.COAL, 1000));
        AABB box = new AABB(h.absolutePos(BlockPos.ZERO)).inflate(16);
        int before = h.getLevel().getEntitiesOfClass(LargeFireball.class, box).size();
        FloraEvents.keyAction(fire, FloraEvents.KEY_FIREBALL);
        FloraEvents.keyAction(fire, FloraEvents.KEY_FIREBALL);
        int after = h.getLevel().getEntitiesOfClass(LargeFireball.class, box).size();
        h.assertTrue(after - before == 1, "one fireball (second press on cooldown), got " + (after - before));
        h.getLevel().getEntitiesOfClass(LargeFireball.class, box).forEach(LargeFireball::discard);
        h.succeed();
    }

    // ------------------------------------------------------------------ EnderTech extras

    public static void exchangerProtectionCreativeAndRadiusKeys(GameTestHelper h) {
        for (int x = 2; x <= 4; x++) {
            for (int z = 2; z <= 4; z++) {
                h.setBlock(new BlockPos(x, 1, z), Blocks.DIRT);
            }
        }
        BlockPos guarded = h.absolutePos(new BlockPos(4, 1, 4));
        PROTECTED.add(guarded);
        FakePlayer fp = FakePlayerFactory.get(h.getLevel(), new GameProfile(UUID.randomUUID(), "[ff-creative]"));
        ItemStack tool = new ItemStack(FeatureContent.EXCHANGERS.get(EnderTechRules.Tier.CREATIVE).get());
        h.assertTrue(ExchangerItem.adjustRadius(tool, 12) == EnderTechRules.EXCHANGER_MAX_RADIUS, "radius max key");
        h.assertTrue(ExchangerItem.adjustRadius(tool, 13) == 1, "radius min key");
        h.assertTrue(ExchangerItem.adjustRadius(tool, 10) == 2, "radius +1 key");
        h.assertTrue(ExchangerItem.adjustRadius(tool, 11) == 1, "radius -1 key");
        fp.setItemInHand(InteractionHand.MAIN_HAND, tool);
        ExchangerService.queue(fp, InteractionHand.MAIN_HAND, h.absolutePos(new BlockPos(3, 1, 3)), WorldDir.UP,
                Blocks.DIRT.defaultBlockState(), Blocks.STONE.defaultBlockState(), 2);
        h.succeedWhen(() -> {
            int stone = 0;
            for (int x = 2; x <= 4; x++) {
                for (int z = 2; z <= 4; z++) {
                    stone += h.getBlockState(new BlockPos(x, 1, z)).is(Blocks.STONE) ? 1 : 0;
                }
            }
            h.assertTrue(stone == 8, "8 swapped without energy or blocks (creative), have " + stone);
            h.assertBlockPresent(Blocks.DIRT, new BlockPos(4, 1, 4));
            h.assertTrue(ExchangerService.running() == 0, "job finished");
            PROTECTED.remove(guarded);
        });
    }

    /** Tank glass records which neighbours are glass, so its borders join up into one pane. */
    public static void tankGlassConnects(GameTestHelper h) {
        BlockState glass = FeatureContent.TANK_PARTS.get(TankStructure.Part.GLASS).get().defaultBlockState();
        for (int x = 2; x <= 4; x++) {
            h.setBlock(new BlockPos(x, 2, 3), glass);
        }
        h.setBlock(new BlockPos(3, 3, 3), glass);
        h.runAtTickTime(2, () -> {
            BlockState mid = h.getBlockState(new BlockPos(3, 2, 3));
            BlockState end = h.getBlockState(new BlockPos(2, 2, 3));
            BlockState top = h.getBlockState(new BlockPos(3, 3, 3));
            h.assertTrue(mid.getValue(net.minecraft.world.level.block.PipeBlock.EAST) && mid.getValue(net.minecraft.world.level.block.PipeBlock.WEST)
                    && mid.getValue(net.minecraft.world.level.block.PipeBlock.UP) && !mid.getValue(net.minecraft.world.level.block.PipeBlock.NORTH),
                    "middle pane joins east, west and up only: " + mid);
            h.assertTrue(end.getValue(net.minecraft.world.level.block.PipeBlock.EAST) && !end.getValue(net.minecraft.world.level.block.PipeBlock.WEST)
                    && !end.getValue(net.minecraft.world.level.block.PipeBlock.UP), "end pane joins east only: " + end);
            h.assertTrue(top.getValue(net.minecraft.world.level.block.PipeBlock.DOWN) && !top.getValue(net.minecraft.world.level.block.PipeBlock.EAST),
                    "top pane joins down only: " + top);
            h.setBlock(new BlockPos(3, 2, 3), Blocks.AIR.defaultBlockState());
        });
        h.runAtTickTime(4, () -> {
            h.assertTrue(!h.getBlockState(new BlockPos(2, 2, 3)).getValue(net.minecraft.world.level.block.PipeBlock.EAST), "border returns when the neighbour goes");
            h.succeed();
        });
    }

    public static void tankControllerItemKeepsContents(GameTestHelper h) {
        BlockState frame = FeatureContent.TANK_PARTS.get(TankStructure.Part.FRAME).get().defaultBlockState();
        BlockState glass = FeatureContent.TANK_PARTS.get(TankStructure.Part.GLASS).get().defaultBlockState();
        for (int x = 2; x <= 4; x++) {
            for (int y = 1; y <= 3; y++) {
                for (int z = 2; z <= 4; z++) {
                    int b = (x == 2 || x == 4 ? 1 : 0) + (y == 1 || y == 3 ? 1 : 0) + (z == 2 || z == 4 ? 1 : 0);
                    if (b > 0) {
                        h.setBlock(new BlockPos(x, y, z), b >= 2 ? frame : glass);
                    }
                }
            }
        }
        BlockPos ctrl = new BlockPos(3, 1, 3);
        h.setBlock(ctrl, FeatureContent.TANK_PARTS.get(TankStructure.Part.CONTROLLER).get().defaultBlockState());
        h.setBlock(new BlockPos(3, 3, 3), FeatureContent.TANK_PARTS.get(TankStructure.Part.VALVE).get().defaultBlockState());
        h.setBlock(new BlockPos(2, 2, 3), FeatureContent.TANK_PARTS.get(TankStructure.Part.ENERGY_INPUT).get().defaultBlockState());
        h.runAtTickTime(5, () -> {
            TankControllerEntity c = h.getBlockEntity(ctrl, TankControllerEntity.class);
            c.recheck();
            c.core().energyInput(() -> 1).receive(20_000, false);
            c.core().valve().fill(McFluidVariant.of(new FluidStack(Fluids.WATER, 1000)), 7_000, false);
            BlockPos abs = h.absolutePos(ctrl);
            ItemStack drop = Block.getDrops(h.getBlockState(ctrl), h.getLevel(), abs, c).stream()
                    .filter(s -> s.is(FeatureContent.TANK_PARTS.get(TankStructure.Part.CONTROLLER).get().asItem())).findFirst().orElseThrow();
            h.assertTrue(drop.has(net.minecraft.core.component.DataComponents.BLOCK_ENTITY_DATA), "controller item carries the tank data");
            BlockPos other = new BlockPos(0, 1, 0);
            h.setBlock(other, FeatureContent.TANK_PARTS.get(TankStructure.Part.CONTROLLER).get().defaultBlockState());
            BlockItem.updateCustomBlockEntityTag(h.getLevel(), null, h.absolutePos(other), drop);
            TankControllerEntity placed = h.getBlockEntity(other, TankControllerEntity.class);
            h.assertTrue(placed.core().fluid().amount() == 7_000, "placed controller restored 7000 mB, has " + placed.core().fluid().amount());
            h.assertTrue(placed.core().energy().stored() == 13_000, "and 13000 RF, has " + placed.core().energy().stored());
            h.succeed();
        });
    }

    // ------------------------------------------------------------------ world modules not yet tested in-world

    private static ChassisBlockEntity chassis(GameTestHelper h, BlockPos rel) {
        h.setBlock(rel, FfContent.CHASSIS.get().defaultBlockState().setValue(ChassisBlock.FACING, Direction.NORTH));
        ChassisBlockEntity be = h.getBlockEntity(rel, ChassisBlockEntity.class);
        be.core().energy().receive(16_000, false);
        return be;
    }

    private static void install(ChassisBlockEntity be, LocalFace f, dev.fieldframe.api.id.FfId id, FfData cfg) {
        ChassisCore.Result r = be.core().install(f, FieldframeRegistry.module(id), cfg);
        if (!r.ok()) {
            throw new IllegalStateException("install failed: " + r);
        }
    }

    private static FfData cfg(dev.fieldframe.api.id.FfId id, Object... kv) {
        FfData d = FieldframeRegistry.module(id).defaultConfig();
        for (int i = 0; i < kv.length; i += 2) {
            d.putAny((String) kv[i], kv[i + 1]);
        }
        return d;
    }

    public static void entityMoverPushesCreature(GameTestHelper h) {
        BlockPos c = new BlockPos(3, 1, 5);
        ChassisBlockEntity be = chassis(h, c);
        install(be, LocalFace.FRONT, BuiltinModules.ENTITY_MOVER, cfg(BuiltinModules.ENTITY_MOVER, "strength", 5));
        Cow cow = h.spawn(net.minecraft.world.entity.EntityTypes.COW, c.north());
        double z0 = cow.getZ();
        h.succeedWhen(() -> h.assertTrue(cow.getZ() < z0 - 0.5, "cow pushed north, z " + z0 + " -> " + cow.getZ()));
    }

    public static void waterwheelGeneratesFromFlowingWater(GameTestHelper h) {
        BlockPos c = new BlockPos(3, 1, 5);
        for (int z = 1; z <= 4; z++) {
            h.setBlock(new BlockPos(3, 0, z), Blocks.STONE);
            h.setBlock(new BlockPos(2, 1, z), Blocks.STONE);
            h.setBlock(new BlockPos(4, 1, z), Blocks.STONE);
        }
        h.setBlock(new BlockPos(3, 1, 0), Blocks.STONE); // close the channel end: water only flows toward the nearest drop
        h.setBlock(new BlockPos(3, 1, 1), Blocks.WATER);
        ChassisBlockEntity be = chassis(h, c);
        be.core().energy().extract(be.core().energy().stored(), false);
        install(be, LocalFace.FRONT, BuiltinModules.WATERWHEEL, null);
        h.succeedWhen(() -> h.assertTrue(be.core().energy().stored() > 0, "flowing water generated RF; status " + be.core().statuses()
                + " front: " + h.getBlockState(c.north()) + " / " + h.getBlockState(c.north(2)) + " / " + h.getBlockState(c.north(3))
                + " trace " + be.core().traceLines()));
    }

    public static void motionHarvesterGeneratesFromMovement(GameTestHelper h) {
        BlockPos c = new BlockPos(3, 1, 5);
        ChassisBlockEntity be = chassis(h, c);
        be.core().energy().extract(be.core().energy().stored(), false);
        install(be, LocalFace.FRONT, BuiltinModules.MOTION_HARVESTER, null);
        Cow cow = h.spawn(net.minecraft.world.entity.EntityTypes.COW, c.north());
        // Keep the cow walking east and west across the box in front of the face (real movement, not setPos).
        h.onEachTick(() -> cow.setDeltaMovement(((h.getTick() / 8) % 2 == 0 ? 0.25 : -0.25), cow.getDeltaMovement().y, 0));
        h.succeedWhen(() -> h.assertTrue(be.core().energy().stored() > 0, "movement generated RF; status " + be.core().statuses()));
    }

    public static void servicePortLimitsAggregateItems(GameTestHelper h) {
        BlockPos c = new BlockPos(3, 1, 3);
        ChassisBlockEntity be = chassis(h, c);
        install(be, LocalFace.LEFT, BuiltinModules.SERVICE_PORT, cfg(BuiltinModules.SERVICE_PORT, "item_limit", 8));
        h.runAtTickTime(3, () -> {
            BlockPos abs = h.absolutePos(c);
            var cap = new dev.fieldframe.neoforge.adapter.NeoHandlers.ExternalItems(h.getLevel().getCapability(Capabilities.Item.BLOCK, abs, Direction.WEST));
            var apple = McItemVariant.of(new ItemStack(Items.APPLE));
            int in = cap.insert(apple, 64, false);
            h.assertTrue(in == 8, "8 accepted this tick, accepted " + in);
            h.assertTrue(cap.insert(apple, 10, false) == 0, "limit shared within the tick");
            h.assertTrue(h.getLevel().getCapability(Capabilities.Energy.BLOCK, abs, Direction.WEST) != null, "RF exposed on the same face");
            h.succeed();
        });
    }

    public static void environmentSensorCountsAnimals(GameTestHelper h) {
        BlockPos c = new BlockPos(3, 1, 5);
        ChassisBlockEntity be = chassis(h, c);
        install(be, LocalFace.FRONT, BuiltinModules.ENVIRONMENT_SENSOR, cfg(BuiltinModules.ENVIRONMENT_SENSOR, "observe", "entities_animal", "range", 2));
        h.spawn(net.minecraft.world.entity.EntityTypes.COW, c.north()).setNoAi(true);
        h.spawn(net.minecraft.world.entity.EntityTypes.COW, c.north(2)).setNoAi(true);
        int ch = be.core().module(LocalFace.FRONT).binding("out");
        h.succeedWhen(() -> h.assertTrue(be.core().control().read(ch) == 2, "2 animals on channel " + (ch + 1) + ", read " + be.core().control().read(ch)));
    }

    // ------------------------------------------------------------------ performance sample

    /** 49 working chassis (heater + fuel engine) on a real server: per-chassis tick cost and saved size. */
    public static void performanceSample(GameTestHelper h) {
        java.util.List<ChassisBlockEntity> all = new java.util.ArrayList<>();
        for (int x = 0; x < 7; x++) {
            for (int z = 0; z < 7; z++) {
                ChassisBlockEntity be = chassis(h, new BlockPos(x, 1, z));
                install(be, LocalFace.TOP, BuiltinModules.HEATER, null);
                install(be, LocalFace.BOTTOM, BuiltinModules.FUEL_ENGINE, null);
                be.core().setBinding(LocalFace.BOTTOM, "fuel", 2);
                be.core().lane(0).insert(McItemVariant.of(new ItemStack(Items.RAW_IRON)), 64, false);
                be.core().lane(2).insert(McItemVariant.of(new ItemStack(Items.COAL)), 64, false);
                all.add(be);
            }
        }
        h.runAtTickTime(40, () -> {
            PerfProbe.reset();
            all.forEach(PerfProbe.TRACKED::add);
            PerfProbe.enabled = true;
        });
        h.runAtTickTime(240, () -> {
            PerfProbe.enabled = false;
            PerfProbe.TRACKED.clear();
            long ticks = PerfProbe.TICKS.get();
            double nsPerChassisTick = PerfProbe.NANOS.get() / (double) Math.max(1, ticks);
            int bytes;
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                NbtIo.writeCompressed(all.get(0).saveWithFullMetadata(h.getLevel().registryAccess()), out);
                bytes = out.size();
            } catch (java.io.IOException e) {
                throw new RuntimeException(e);
            }
            Fieldframe.LOG.info("FIELDFRAME_PERF edition=neoforge-26.1.2 chassis=49 samples={} ns_per_chassis_tick={} ms_per_server_tick={} saved_bytes_gzip={}",
                    ticks, Math.round(nsPerChassisTick), String.format("%.3f", nsPerChassisTick * 49 / 1e6), bytes);
            h.assertTrue(ticks >= 49 * 150, "measured enough ticks: " + ticks);
            h.succeed();
        });
    }
}
