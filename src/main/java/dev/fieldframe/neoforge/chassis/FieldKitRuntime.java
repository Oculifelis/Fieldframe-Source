package dev.fieldframe.neoforge.chassis;

import dev.fieldframe.api.chassis.FieldKits;
import dev.fieldframe.api.face.LocalFace;
import dev.fieldframe.api.resource.ItemVariant;
import dev.fieldframe.core.chassis.ChassisCore;
import dev.fieldframe.core.chassis.InstalledModule;
import dev.fieldframe.core.modules.BuiltinModules;
import dev.fieldframe.neoforge.Fieldframe;
import dev.fieldframe.neoforge.adapter.McItemVariant;
import dev.fieldframe.neoforge.adapter.McVariantCodec;
import dev.fieldframe.neoforge.adapter.NbtBridge;
import dev.fieldframe.neoforge.adapter.RegistryHolder;
import dev.fieldframe.neoforge.feature.ItemEnergy;
import dev.fieldframe.neoforge.item.FieldKitItem;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Server side of the Field Kit: one {@link ChassisCore} per kit, keyed by the id stored on the item and ticked while
 * the kit is carried. The item holds the saved core; it is written back when the player changes something (at once)
 * and while the kit works (at most once a second), so the stack's data never runs far behind.
 */
public final class FieldKitRuntime {
    private FieldKitRuntime() {
    }

    static final String ID = "kit_id";
    static final String CORE = "kit";
    public static final String RF = "kit_rf";
    public static final String CAP = "kit_cap";
    public static final String TIER = "kit_tier";
    public static final String MODULES = "kit_modules";
    private static final int SAVE_EVERY = 20;
    private static final int EVICT_AFTER = 600;
    private static final int CHARGE_EVERY = 10;

    public static final class Kit {
        public final UUID id;
        public final ChassisCore core;
        long time;
        long lastTick = -1;
        long lastSave;
        boolean unsaved;

        Kit(UUID id) {
            this.id = id;
            this.core = new ChassisCore(FieldKits.SPEC, McVariantCodec.INSTANCE, () -> time);
            core.setAddress("kit:" + id);
        }
    }

    private static final Map<UUID, Kit> KITS = new HashMap<>();
    /** Player -> the kit whose machine screen they have open. */
    private static final Map<UUID, UUID> OPEN = new HashMap<>();

    public static UUID id(ItemStack s) {
        String v = ItemEnergy.data(s).getStringOr(ID, "");
        try {
            return v.isEmpty() ? null : UUID.fromString(v);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** The kit for {@code stack}, loaded from the item the first time it is seen. Server only. */
    public static Kit get(ItemStack stack, ServerLevel level) {
        UUID id = id(stack);
        if (id == null) {
            UUID fresh = UUID.randomUUID();
            ItemEnergy.editData(stack, t -> t.putString(ID, fresh.toString()));
            id = fresh;
        }
        Kit kit = KITS.get(id);
        if (kit == null) {
            kit = new Kit(id);
            kit.time = level.getGameTime();
            CompoundTag saved = ItemEnergy.data(stack).getCompoundOrEmpty(CORE);
            if (!saved.isEmpty()) {
                RegistryHolder.set(level.registryAccess());
                for (String problem : kit.core.load(NbtBridge.fromTag(saved))) {
                    Fieldframe.LOG.warn("Fieldframe Field Kit {}: {}", id, problem);
                }
            }
            KITS.put(id, kit);
        }
        return kit;
    }

    public static void tick(ItemStack stack, ServerLevel level, Player carrier) {
        Kit kit = get(stack, level);
        long now = level.getGameTime();
        if (kit.lastTick == now) {
            return; // a copied stack with the same id: one tick per game tick
        }
        kit.lastTick = now;
        kit.time = now;
        if (kit.core.ownerId().isEmpty()) {
            kit.core.setOwner(carrier.getUUID().toString(), carrier.getName().getString());
        }
        long pending = ItemEnergy.get(stack);
        if (pending > 0) {
            // RF a charger put into the item while it was away (see Fieldframe#registerCapabilities).
            long got = kit.core.energy().receive(pending, false);
            if (got >= pending) {
                stack.remove(dev.fieldframe.neoforge.feature.FeatureContent.ENERGY.get());
            } else {
                ItemEnergy.set(stack, pending - got);
            }
        }
        KitWorld world = new KitWorld(level, carrier);
        kit.core.tick(world);
        for (Object[] s : kit.core.drainSpill()) {
            if (s[0] instanceof ItemVariant v && s[1] instanceof Integer n) {
                give(carrier, McItemVariant.toStack(v, n));
            }
        }
        if (now % CHARGE_EVERY == 0 && hasModule(kit.core, BuiltinModules.ITEM_CHARGER)) {
            chargeCarried(kit, carrier, world);
        }
        if (kit.core.consumeDirty()) {
            kit.unsaved = true;
        }
        if (kit.unsaved && now - kit.lastSave >= SAVE_EVERY) {
            save(stack, kit, level);
        }
    }

    /** With an Item Charger installed, the kit also tops up what the carrier holds and wears. */
    private static void chargeCarried(Kit kit, Player carrier, KitWorld world) {
        long budget = FieldKits.SPEC.energyOutPerTick() * CHARGE_EVERY;
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack s = carrier.getItemBySlot(slot);
            long avail = Math.min(budget, kit.core.energy().stored());
            if (avail <= 0) {
                return;
            }
            if (s.isEmpty() || s.getItem() instanceof FieldKitItem || s.getCount() != 1) {
                continue;
            }
            var r = world.charge(McItemVariant.of(s), avail);
            if (r == null || r.accepted() <= 0) {
                continue;
            }
            long taken = kit.core.energy().extract(r.accepted(), false);
            budget -= taken;
            carrier.setItemSlot(slot, McItemVariant.toStack(r.result(), 1));
        }
    }

    static boolean hasModule(ChassisCore core, dev.fieldframe.api.id.FfId id) {
        for (LocalFace f : LocalFace.ALL) {
            InstalledModule m = core.module(f);
            if (m != null && id.equals(m.moduleId())) {
                return true;
            }
        }
        return false;
    }

    /** Writes the kit's state onto its item: the core, plus what the tooltip and energy bar show. */
    public static void save(ItemStack stack, Kit kit, ServerLevel level) {
        RegistryHolder.set(level.registryAccess());
        CompoundTag saved = NbtBridge.toTag(kit.core.save());
        StringBuilder modules = new StringBuilder();
        for (LocalFace f : LocalFace.ALL) {
            InstalledModule m = kit.core.module(f);
            if (m != null) {
                modules.append(modules.isEmpty() ? "" : ",").append(m.moduleId());
            }
        }
        ItemEnergy.editData(stack, t -> {
            t.put(CORE, saved);
            t.putLong(RF, kit.core.energy().stored());
            t.putLong(CAP, kit.core.energy().capacity());
            t.putInt(TIER, kit.core.tier().level());
            t.putString(MODULES, modules.toString());
        });
        kit.lastSave = level.getGameTime();
        kit.unsaved = false;
    }

    /** The carried kit stack with {@code id}, or EMPTY. */
    public static ItemStack find(Player p, UUID id) {
        var inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.getItem() instanceof FieldKitItem && id.equals(id(s))) {
                return s;
            }
        }
        if (net.neoforged.fml.ModList.get().isLoaded("curios")) {
            return dev.fieldframe.neoforge.compat.curios.KitCurio.find(p, s -> s.getItem() instanceof FieldKitItem && id.equals(id(s)));
        }
        return ItemStack.EMPTY;
    }

    /** The first kit the player carries (inventory, then a Curios slot), or EMPTY. */
    public static ItemStack firstCarried(Player p) {
        var inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (inv.getItem(i).getItem() instanceof FieldKitItem) {
                return inv.getItem(i);
            }
        }
        if (net.neoforged.fml.ModList.get().isLoaded("curios")) {
            return dev.fieldframe.neoforge.compat.curios.KitCurio.find(p, s -> s.getItem() instanceof FieldKitItem);
        }
        return ItemStack.EMPTY;
    }

    /** Saves a kit the player just changed, if they still carry it. */
    public static void saveNow(Player p, Kit kit) {
        ItemStack s = find(p, kit.id);
        if (!s.isEmpty() && p.level() instanceof ServerLevel sl) {
            save(s, kit, sl);
        }
    }

    /** Saves a kit lying on the ground (dropped items stop inventory ticks). */
    public static void saveDropped(ItemStack stack, ServerLevel level) {
        UUID id = id(stack);
        Kit kit = id == null ? null : KITS.get(id);
        if (kit != null && kit.unsaved) {
            save(stack, kit, level);
        }
    }

    public static void open(Player p, Kit kit) {
        OPEN.put(p.getUUID(), kit.id);
    }

    /** The kit whose machine screen {@code p} has open, if they still carry it. */
    public static Kit openKit(Player p) {
        UUID id = OPEN.get(p.getUUID());
        if (id == null || find(p, id).isEmpty()) {
            return null;
        }
        return KITS.get(id);
    }

    /** Forgets kits nobody has carried for a while (they reload from the item). */
    public static void sweep(MinecraftServer server) {
        if (server.getTickCount() % 200 != 0 || KITS.isEmpty()) {
            return;
        }
        long now = server.overworld().getGameTime();
        KITS.values().removeIf(k -> now - k.lastTick > EVICT_AFTER && !k.unsaved);
        OPEN.keySet().removeIf(u -> server.getPlayerList().getPlayer(u) == null);
    }

    static void give(Player p, ItemStack s) {
        if (!s.isEmpty() && !p.getInventory().add(s)) {
            p.drop(s, false, net.minecraft.util.Prediction.SERVER_ONLY);
        }
    }
}
