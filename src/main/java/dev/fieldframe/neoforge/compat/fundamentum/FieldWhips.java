package dev.fieldframe.neoforge.compat.fundamentum;

import com.fundamentum.whip.WhipCordFx;
import com.fundamentum.whip.WhipDefinition;
import com.fundamentum.whip.WhipFx;
import com.fundamentum.whip.WhipHit;
import com.fundamentum.whip.WhipHooks;
import com.fundamentum.whip.Whips;
import dev.fieldframe.neoforge.Fieldframe;
import dev.fieldframe.neoforge.feature.FeatureContent;
import dev.fieldframe.neoforge.item.ChargedGearItem;
import dev.fieldframe.neoforge.registry.FfContent;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.transfer.energy.ItemAccessEnergyHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fieldframe's electric whips use the required Fundamentum library.
 * Each swing spends RF from the whip; a charged swing hits harder and its
 * cracking tip shocks, an empty one hits at {@link #EMPTY_DAMAGE} and never cracks for a crit. Numbers are picked
 * against Ostentum's whips: the Arc Whip sits with the Glow Berry Whip (5 blocks), the Tesla Whip just above the
 * Thunderstorm Whip (6 blocks, a longer chain) because it needs a reinforced kit and RF to run.
 */
public final class FieldWhips {
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Fieldframe.MOD_ID);

    public static final DeferredItem<ChargedGearItem> ARC_WHIP = ITEMS.registerItem("arc_whip",
            p -> new ChargedGearItem(p, new ChargedGearItem.Charge(40_000, 1_000, 250, 0)), p -> p.stacksTo(1).enchantable(12));
    public static final DeferredItem<ChargedGearItem> TESLA_WHIP = ITEMS.registerItem("tesla_whip",
            p -> new ChargedGearItem(p, new ChargedGearItem.Charge(100_000, 2_500, 400, 150)), p -> p.stacksTo(1).rarity(Rarity.UNCOMMON).enchantable(15));

    /** Damage scale of a swing made without the RF for it. */
    public static final float EMPTY_DAMAGE = 0.5F;
    /** Shock added to every hit of a charged swing. */
    public static final float SHOCK = 2.0F;
    static final int ARC_COLOUR = 0xFFAEE8FF;

    /** Players whose current swing was paid for. */
    private static final Set<UUID> CHARGED = ConcurrentHashMap.newKeySet();

    public static void init(IEventBus modBus) {
        ITEMS.register(modBus);
        modBus.addListener(FieldWhips::capabilities);
        modBus.addListener(FieldWhips::creativeTab);
        Whips.register(ARC_WHIP.getId(), arc());
        Whips.register(TESLA_WHIP.getId(), tesla());
    }

    /** Whether the player's current swing was paid for (charged hits). */
    public static boolean paid(ServerPlayer player) {
        return CHARGED.contains(player.getUUID());
    }

    private static Identifier lash(String whip) {
        return Identifier.fromNamespaceAndPath(Fieldframe.MOD_ID, "textures/entity/whip/" + whip + ".png");
    }

    /** Tier 1: a copper lash fed from a cell in the grip. A charged cracking tip shocks and staggers (Slowness II, 1 s). */
    static WhipDefinition arc() {
        return WhipDefinition.builder(ARC_WHIP.getId())
                .tuning(t -> t.length(5.0).damage(6).cooldownTicks(14))
                .coiled().texture(lash("arc_whip")).width(0.12F, 0.05F)
                // The painted copper as is, lit by the world; WhipChargeClient adds the unlit current, halo and arcs
                // while charged (the ENERGY and LIGHTNING shaders wash the braid out, and their halo shows even empty).
                .cordFx(WhipCordFx.NONE).glow(0)
                .sounds(SoundEvents.PLAYER_ATTACK_SWEEP, SoundEvents.COPPER_BULB_TURN_ON)
                .crackParticle(null) // no white sparks (Oculifelis, 2026-11-04)
                .hooks(new Powered(0, 20, 1))
                .build();
    }

    /**
     * Tier 2, from the Arc Whip and a Reinforced Upgrade Kit: longer and harder. A charged cracking tip stuns its target
     * (Slowness V and Weakness, 1.5 s) and arcs on to up to four more hostiles, each within six blocks of the last, for
     * 6, 5, 4 and 3; every arc costs RF and the chain stops when the whip runs dry.
     */
    static WhipDefinition tesla() {
        return WhipDefinition.builder(TESLA_WHIP.getId())
                .tuning(t -> t.length(6.0).damage(9).cooldownTicks(16))
                .coiled().texture(lash("tesla_whip")).width(0.14F, 0.055F)
                .cordFx(WhipCordFx.NONE).glow(0)
                .shockwave(0xC0AEE8FF)
                .sounds(SoundEvents.PLAYER_ATTACK_SWEEP, SoundEvents.TRIDENT_THUNDER.value())
                .crackParticle(null) // no white sparks (Oculifelis, 2026-11-04)
                .hooks(new Powered(4, 30, 4))
                .build();
    }

    /** Pays for the swing, weakens unpaid hits, shocks paid tip crits and, with {@code arcs > 0}, chains. */
    record Powered(int arcs, int stunTicks, int stunAmplifier) implements WhipHooks {
        @Override
        public void swingStart(ServerPlayer player, ItemStack stack, WhipDefinition whip) {
            if (stack.getItem() instanceof ChargedGearItem w && w.spend(stack, w.charge().perUse())) {
                CHARGED.add(player.getUUID());
            } else {
                CHARGED.remove(player.getUUID());
            }
        }

        @Override
        public void swingEnd(ServerPlayer player, ItemStack stack, WhipDefinition whip, int targets) {
            CHARGED.remove(player.getUUID());
        }

        @Override
        public void cordHit(WhipHit hit) {
            if (CHARGED.contains(hit.player.getUUID())) {
                hit.damage += SHOCK;
                return;
            }
            if (hit.crit) {
                hit.damage /= (float) hit.whip.tuning.tipCritMultiplier();
                hit.crit = false;
            }
            hit.damage *= EMPTY_DAMAGE;
        }

        @Override
        public void landed(WhipHit hit, boolean hurt) {
            LivingEntity first = hit.living();
            if (!hurt || !hit.crit || first == null || !CHARGED.contains(hit.player.getUUID())
                    || !(hit.player.level() instanceof ServerLevel level)) {
                return;
            }
            first.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, stunTicks, stunAmplifier), hit.player);
            if (arcs > 0) {
                first.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, stunTicks, 0), hit.player);
            }
            chain(hit, level, first);
        }

        private void chain(WhipHit hit, ServerLevel level, LivingEntity first) {
            if (arcs <= 0 || !(hit.stack.getItem() instanceof ChargedGearItem w)) {
                return;
            }
            List<LivingEntity> chain = new ArrayList<>(List.of(first));
            LivingEntity from = first;
            float damage = 6.0F;
            for (int hop = 0; hop < arcs; hop++) {
                LivingEntity next = null;
                for (LivingEntity e : WhipFx.hostilesNear(hit.player, from.getBoundingBox().getCenter(), 6)) {
                    if (!chain.contains(e)) {
                        next = e;
                        break;
                    }
                }
                if (next == null || !w.spend(hit.stack, w.charge().perArc())) {
                    break;
                }
                Vec3 a = from.getBoundingBox().getCenter(), b = next.getBoundingBox().getCenter();
                WhipFx.beam(level, a, b, ARC_COLOUR, 0.1F, 6, true);
                if (next.hurtServer(level, hit.whip.damage.make(hit.player, hit.stack), damage)) {
                    next.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 20, 3), hit.player);
                }
                chain.add(next);
                from = next;
                damage -= 1.0F;
            }
            if (chain.size() > 1) {
                level.playSound(null, first.getX(), first.getY(), first.getZ(), SoundEvents.LIGHTNING_BOLT_IMPACT, SoundSource.PLAYERS, 0.5F, 1.6F);
            }
        }
    }

    /** RF goes in like any RF item (Item Charger, charge pads), never out. */
    private static void capabilities(RegisterCapabilitiesEvent e) {
        for (DeferredItem<ChargedGearItem> whip : List.of(ARC_WHIP, TESLA_WHIP)) {
            ChargedGearItem.Charge c = whip.get().charge();
            e.registerItem(Capabilities.Energy.ITEM, (stack, access) -> new ItemAccessEnergyHandler(access, FeatureContent.ENERGY.get(),
                    c.capacity(), c.receivePerTick(), 0), whip.get());
        }
    }

    private static void creativeTab(BuildCreativeModeTabContentsEvent e) {
        if (e.getTabKey().equals(FfContent.TAB.getKey())) {
            for (DeferredItem<ChargedGearItem> whip : List.of(ARC_WHIP, TESLA_WHIP)) {
                e.accept(whip.get());
                e.accept(whip.get().charged());
            }
        }
    }

    private FieldWhips() {}
}
