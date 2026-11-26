package dev.fieldframe.forge.item;

import dev.fieldframe.api.FieldframeRegistry;
import dev.fieldframe.api.chassis.FieldKits;
import dev.fieldframe.api.face.LocalFace;
import dev.fieldframe.api.id.FfId;
import dev.fieldframe.api.module.ModuleDefinition;
import dev.fieldframe.core.chassis.ChassisCore;
import dev.fieldframe.core.chassis.InstalledModule;
import dev.fieldframe.core.modules.power.HandCrank;
import dev.fieldframe.forge.chassis.FieldKitRuntime;
import dev.fieldframe.forge.feature.ItemEnergy;
import dev.fieldframe.forge.registry.FfContent;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * Field Kit: a pocket chassis that works anywhere in the inventory (see {@link FieldKits}). Use with a module, upgrade
 * or tier kit in the other hand to install it; sneak-use opens the machine screen; use opens the kit's lanes, or
 * turns its Hand Crank while held when one is installed.
 */
public final class FieldKitItem extends Item {
    /**
     * Stands in for a block position in machine-screen packets aimed at the player's open kit. No world position
     * packs to it (x and z are beyond the world border).
     */
    public static final BlockPos KIT_POS = new BlockPos(-33_554_000, -2000, -33_554_000);
    private static final int CRANK_EVERY = 4;

    public FieldKitItem(Properties props) {
        super(props);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack self = player.getItemInHand(hand);
        ItemStack other = player.getItemInHand(hand == InteractionHand.MAIN_HAND ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND);
        boolean installing = other.getItem() instanceof ModuleItem || other.getItem() instanceof UpgradeItem || other.getItem() instanceof TierKitItem;
        if (!installing && !player.isShiftKeyDown() && modules(player.getItemInHand(hand)).contains(HandCrank.ID.toString())) {
            player.startUsingItem(hand);
            return InteractionResultHolder.consume(self);
        }
        if (level.isClientSide || !(level instanceof ServerLevel sl)) {
            return InteractionResultHolder.success(self);
        }
        FieldKitRuntime.Kit kit = FieldKitRuntime.get(player.getItemInHand(hand), sl);
        if (installing) {
            install(player, kit, other);
            FieldKitRuntime.save(player.getItemInHand(hand), kit, sl);
        } else if (!(player instanceof ServerPlayer sp)) {
            return InteractionResultHolder.pass(self);
        } else if (player.isShiftKeyDown()) {
            dev.fieldframe.forge.network.FfNetwork.openKit(sp, kit, firstFace(kit.core));
        } else {
            dev.fieldframe.forge.menu.LaneAccessMenu.openKit(sp, kit);
        }
        return InteractionResultHolder.success(self);
    }

    /** RF the item can take from outside: free room in the kit as last saved. */
    public static int room(ItemStack stack) {
        var t = ItemEnergy.data(stack);
        long cap = longOr(t, FieldKitRuntime.CAP, FieldKits.SPEC.energyCapacity());
        return (int) Math.max(0, Math.min(Integer.MAX_VALUE, cap - t.getLong(FieldKitRuntime.RF)));
    }

    public static LocalFace firstFace(ChassisCore core) {
        for (LocalFace f : FieldKits.FACE_ORDER) {
            if (core.module(f) != null) {
                return f;
            }
        }
        return LocalFace.FRONT;
    }

    /** Server: installs the module, upgrade or tier kit held in the other hand. */
    static void install(Player p, FieldKitRuntime.Kit kit, ItemStack held) {
        ChassisCore core = kit.core;
        ChassisCore.Result r;
        String sound = "machine.install";
        if (held.getItem() instanceof ModuleItem mi) {
            ModuleDefinition def = FieldframeRegistry.module(mi.moduleId());
            if (def == null || !FieldKits.allowed(mi.moduleId())) {
                p.displayClientMessage(Component.translatable("fieldframe.kit.not_allowed"), true);
                return;
            }
            r = ChassisCore.Result.fail("NO_FACE", "");
            int open = FieldKits.faces(core.tier().level());
            for (int i = 0; i < open && !r.ok(); i++) {
                LocalFace f = FieldKits.FACE_ORDER[i];
                if (core.module(f) == null) {
                    r = core.install(f, def, ModuleItem.storedConfig(held));
                }
            }
            if (!r.ok() && r.code().equals("NO_FACE")) {
                p.displayClientMessage(Component.translatable("fieldframe.kit.no_face", open), true);
                return;
            }
        } else if (held.getItem() instanceof UpgradeItem ui) {
            var up = FieldframeRegistry.upgrade(ui.upgradeId());
            r = ChassisCore.Result.fail("NO_FREE_BAY", "");
            for (int bay = 0; bay < core.serviceBays() && !r.ok() && up != null; bay++) {
                if (core.upgrade(bay) == null) {
                    r = core.installUpgrade(bay, up);
                }
            }
            sound = "machine.upgrade";
        } else if (held.getItem() instanceof TierKitItem tk) {
            r = core.upgradeTier(FieldframeRegistry.tier(tk.tierId()));
            sound = "machine.tier_up";
        } else {
            return;
        }
        if (!r.ok()) {
            p.displayClientMessage(Component.translatable("fieldframe.result." + r.code().toLowerCase(java.util.Locale.ROOT), "", r.detail()), true);
            return;
        }
        if (!p.getAbilities().instabuild) {
            held.shrink(1);
        }
        p.level().playSound(null, p.blockPosition(), FfContent.sound(sound), SoundSource.PLAYERS, 0.8f, 1f);
    }

    @Override
    public void inventoryTick(ItemStack stack, Level level, Entity owner, int slot, boolean selected) {
        if (level instanceof ServerLevel sl && owner instanceof Player p && !p.isSpectator()) {
            FieldKitRuntime.tick(stack, sl, p);
        }
    }

    @Override
    public boolean onEntityItemUpdate(ItemStack stack, ItemEntity entity) {
        if (entity.level() instanceof ServerLevel sl) {
            FieldKitRuntime.saveDropped(stack, sl);
        }
        return false;
    }

    /** RF from any charger waits on the item (up to the kit's free room) until the kit next ticks. */
    @Override
    public net.minecraftforge.common.capabilities.ICapabilityProvider initCapabilities(ItemStack stack, @org.jetbrains.annotations.Nullable net.minecraft.nbt.CompoundTag nbt) {
        return ItemEnergy.provider(stack, () -> room(stack), FieldKits.SPEC.energyInPerTick());
    }

    @Override
    public boolean shouldCauseReequipAnimation(ItemStack oldStack, ItemStack newStack, boolean slotChanged) {
        return slotChanged || oldStack.getItem() != newStack.getItem();
    }

    // ------------------------------------------------------------------ Hand Crank: turned while use is held

    @Override
    public UseAnim getUseAnimation(ItemStack stack) {
        return UseAnim.BRUSH;
    }

    @Override
    public int getUseDuration(ItemStack stack) {
        return modules(stack).contains(HandCrank.ID.toString()) ? 72_000 : 0;
    }

    @Override
    public void onUseTick(Level level, LivingEntity user, ItemStack stack, int ticksRemaining) {
        if (!(level instanceof ServerLevel sl) || !(user instanceof Player p) || ticksRemaining % CRANK_EVERY != 0) {
            return;
        }
        FieldKitRuntime.Kit kit = FieldKitRuntime.get(stack, sl);
        for (LocalFace f : LocalFace.ALL) {
            InstalledModule m = kit.core.module(f);
            if (m != null && HandCrank.ID.equals(m.moduleId()) && kit.core.playerUse(f, false)) {
                p.causeFoodExhaustion(0.05f);
                return;
            }
        }
    }

    // ------------------------------------------------------------------ what the client shows

    private static String modules(ItemStack stack) {
        return ItemEnergy.data(stack).getString(FieldKitRuntime.MODULES);
    }

    private static long longOr(net.minecraft.nbt.CompoundTag t, String key, long fallback) {
        return t.contains(key) ? t.getLong(key) : fallback;
    }

    @Override
    public boolean isBarVisible(ItemStack stack) {
        return ItemEnergy.data(stack).getLong(FieldKitRuntime.CAP) > 0;
    }

    @Override
    public int getBarWidth(ItemStack stack) {
        var t = ItemEnergy.data(stack);
        long cap = Math.max(1, longOr(t, FieldKitRuntime.CAP, 1L));
        return (int) Math.round(13.0 * Math.min(cap, t.getLong(FieldKitRuntime.RF) + ItemEnergy.get(stack)) / cap);
    }

    @Override
    public int getBarColor(ItemStack stack) {
        return 0xE0443A;
    }

    @Override
    public void appendHoverText(ItemStack stack, @org.jetbrains.annotations.Nullable Level level, List<Component> tip, TooltipFlag flag) {
        var t = ItemEnergy.data(stack);
        int tier = t.getInt(FieldKitRuntime.TIER);
        long cap = longOr(t, FieldKitRuntime.CAP, FieldKits.SPEC.energyCapacity());
        tip.add(Component.translatable("fieldframe.kit.energy", String.format("%,d", t.getLong(FieldKitRuntime.RF)), String.format("%,d", cap))
                .withStyle(ChatFormatting.GRAY));
        String mods = t.getString(FieldKitRuntime.MODULES);
        int used = mods.isEmpty() ? 0 : mods.split(",").length;
        tip.add(Component.translatable("fieldframe.kit.faces", used, FieldKits.faces(tier)).withStyle(ChatFormatting.GRAY));
        for (String id : mods.isEmpty() ? new String[0] : mods.split(",")) {
            FfId mid = FfId.tryParse(id);
            ModuleItem item = mid == null ? null : FfContent.moduleItem(mid);
            tip.add(Component.literal(" • ").append(item == null ? Component.literal(id) : item.getName(item.getDefaultInstance())).withStyle(ChatFormatting.DARK_GRAY));
        }
        tip.add(Component.translatable("fieldframe.kit.how").withStyle(ChatFormatting.DARK_GRAY));
    }
}
