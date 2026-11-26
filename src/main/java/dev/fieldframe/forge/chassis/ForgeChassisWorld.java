package dev.fieldframe.forge.chassis;

import dev.fieldframe.api.face.LocalFace;
import dev.fieldframe.api.face.Orientation;
import dev.fieldframe.api.module.ChassisWorld;
import dev.fieldframe.api.process.RecipeSource;
import dev.fieldframe.api.resource.EnergyStore;
import dev.fieldframe.api.resource.FluidStore;
import dev.fieldframe.api.resource.ItemStore;
import dev.fieldframe.api.resource.ItemVariant;
import dev.fieldframe.api.resource.TagLookup;
import dev.fieldframe.forge.adapter.Dirs;
import dev.fieldframe.forge.adapter.ForgeHandlers;
import dev.fieldframe.forge.adapter.McItemVariant;
import dev.fieldframe.forge.adapter.McTagLookup;
import dev.fieldframe.forge.recipe.RecipeBridge;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.Containers;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.ForgeHooks;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;

/**
 * {@link ChassisWorld} over a live server level. Only the six immediately adjacent positions are consulted;
 * unloaded neighbours are reported, never loaded. Capabilities are resolved per use and not cached across
 * ticks, so a removed or invalidated neighbour handler is never retained.
 */
public final class ForgeChassisWorld implements ChassisWorld {
    private final Level level;
    private final BlockPos pos;
    private final Orientation orientation;
    private final String ownerId;
    private final String ownerName;
    private ForgeWorldActions actions;

    public ForgeChassisWorld(Level level, BlockPos pos, Orientation orientation) {
        this(level, pos, orientation, "", "");
    }

    public ForgeChassisWorld(Level level, BlockPos pos, Orientation orientation, String ownerId, String ownerName) {
        this.level = level;
        this.pos = pos;
        this.orientation = orientation;
        this.ownerId = ownerId;
        this.ownerName = ownerName;
    }

    @Override
    public dev.fieldframe.api.world.WorldActions actions() {
        if (actions == null && level instanceof net.minecraft.server.level.ServerLevel sl) {
            actions = new ForgeWorldActions(sl, pos, orientation, ownerId, ownerName);
        }
        return actions;
    }

    @Override
    public dev.fieldframe.api.data.FfData itemData(ItemVariant v, String key) {
        net.minecraft.nbt.CompoundTag t = McItemVariant.toStack(v, 1).getTagElement("fieldframe");
        return t == null || !t.contains(key) ? null : dev.fieldframe.forge.adapter.NbtBridge.fromTag(t.getCompound(key));
    }

    @Override
    public ItemVariant withItemData(ItemVariant v, String key, dev.fieldframe.api.data.FfData data) {
        ItemStack s = McItemVariant.toStack(v, 1);
        if (s.isEmpty()) {
            return null;
        }
        s.getOrCreateTagElement("fieldframe").put(key, dev.fieldframe.forge.adapter.NbtBridge.toTag(data));
        return McItemVariant.of(s);
    }

    @Override
    public ItemVariant itemVariant(dev.fieldframe.api.id.FfId id) {
        var item = net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(new net.minecraft.resources.ResourceLocation(id.namespace(), id.path()));
        return item == null || item == net.minecraft.world.item.Items.AIR ? null
                : dev.fieldframe.forge.adapter.McItemVariant.of(new net.minecraft.world.item.ItemStack(item));
    }

    @Override
    public dev.fieldframe.api.resource.FluidVariant fluidVariant(dev.fieldframe.api.id.FfId id) {
        var fluid = net.minecraftforge.registries.ForgeRegistries.FLUIDS.getValue(new net.minecraft.resources.ResourceLocation(id.namespace(), id.path()));
        return fluid == null || fluid == net.minecraft.world.level.material.Fluids.EMPTY ? null
                : dev.fieldframe.forge.adapter.McFluidVariant.of(new net.minecraftforge.fluids.FluidStack(fluid, 1));
    }

    @Override
    public ItemVariant brew(ItemVariant base, ItemVariant ingredient) {
        net.minecraft.world.item.ItemStack b = dev.fieldframe.forge.adapter.McItemVariant.toStack(base, 1);
        net.minecraft.world.item.ItemStack i = dev.fieldframe.forge.adapter.McItemVariant.toStack(ingredient, 1);
        if (b.isEmpty() || i.isEmpty() || !net.minecraftforge.common.brewing.BrewingRecipeRegistry.hasOutput(b, i)) {
            return null;
        }
        net.minecraft.world.item.ItemStack r = net.minecraftforge.common.brewing.BrewingRecipeRegistry.getOutput(b, i);
        return r.isEmpty() || net.minecraft.world.item.ItemStack.isSameItemSameTags(r, b) ? null
                : dev.fieldframe.forge.adapter.McItemVariant.of(r);
    }

    @Override
    public ItemVariant enchant(ItemVariant item, int cost, long seed) {
        net.minecraft.world.item.ItemStack s = dev.fieldframe.forge.adapter.McItemVariant.toStack(item, 1);
        if (s.isEmpty() || !s.isEnchantable()) {
            return null;
        }
        net.minecraft.world.item.ItemStack r = net.minecraft.world.item.enchantment.EnchantmentHelper.enchantItem(
                net.minecraft.util.RandomSource.create(seed), s.copy(), cost, false);
        return r.isEmpty() || !(r.isEnchanted() || r.is(net.minecraft.world.item.Items.ENCHANTED_BOOK)) ? null
                : dev.fieldframe.forge.adapter.McItemVariant.of(r);
    }

    @Override
    public int foodValue(ItemVariant v) {
        var food = dev.fieldframe.forge.adapter.McItemVariant.toStack(v, 1).getFoodProperties(null);
        return food == null ? 0 : Math.max(0, food.getNutrition());
    }

    @Override
    public int enchantmentLevels(ItemVariant v) {
        int sum = 0;
        for (int lvl : net.minecraft.world.item.enchantment.EnchantmentHelper.getEnchantments(
                dev.fieldframe.forge.adapter.McItemVariant.toStack(v, 1)).values()) {
            sum += lvl;
        }
        return sum;
    }

    @Override
    public ChargeResult charge(ItemVariant v, long maxRf) {
        net.minecraft.world.item.ItemStack s = dev.fieldframe.forge.adapter.McItemVariant.toStack(v, 1);
        if (s.isEmpty()) {
            return null;
        }
        var handler = s.getCapability(net.minecraftforge.common.capabilities.ForgeCapabilities.ENERGY).resolve().orElse(null);
        if (handler == null || handler.getMaxEnergyStored() <= 0 || !handler.canReceive()) {
            return null;
        }
        long accepted = Math.max(0, handler.receiveEnergy((int) Math.min(Integer.MAX_VALUE, Math.max(0, maxRf)), false));
        boolean full = handler.getEnergyStored() >= handler.getMaxEnergyStored();
        return new ChargeResult(dev.fieldframe.forge.adapter.McItemVariant.of(s), accepted, full);
    }

    @Override
    public CraftResult craft(java.util.List<ItemVariant> grid) {
        var menu = new net.minecraft.world.inventory.AbstractContainerMenu(null, -1) {
            @Override
            public ItemStack quickMoveStack(net.minecraft.world.entity.player.Player p, int i) {
                return ItemStack.EMPTY;
            }

            @Override
            public boolean stillValid(net.minecraft.world.entity.player.Player p) {
                return false;
            }
        };
        var container = new net.minecraft.world.inventory.TransientCraftingContainer(menu, 3, 3);
        for (int i = 0; i < 9 && i < grid.size(); i++) {
            ItemVariant v = grid.get(i);
            container.setItem(i, v == null ? ItemStack.EMPTY : McItemVariant.toStack(v, 1));
        }
        var recipe = level.getRecipeManager().getRecipeFor(net.minecraft.world.item.crafting.RecipeType.CRAFTING, container, level);
        if (recipe.isEmpty()) {
            return null;
        }
        ItemStack out = recipe.get().assemble(container, level.registryAccess());
        if (out.isEmpty()) {
            return null;
        }
        java.util.List<ItemVariant> rem = new java.util.ArrayList<>();
        for (ItemStack r : recipe.get().getRemainingItems(container)) {
            if (!r.isEmpty()) {
                for (int n = 0; n < r.getCount(); n++) {
                    rem.add(McItemVariant.of(r));
                }
            }
        }
        return new CraftResult(McItemVariant.of(out), out.getCount(), rem);
    }

    private Direction dir(LocalFace face) {
        return Dirs.toMc(orientation.toWorld(face));
    }

    @Override
    public long gameTime() {
        return level.getGameTime();
    }

    @Override
    public boolean isNeighborLoaded(LocalFace face) {
        return level.isLoaded(pos.relative(dir(face)));
    }

    private <T> T neighborCap(LocalFace face, Capability<T> cap) {
        Direction d = dir(face);
        BlockPos np = pos.relative(d);
        if (!level.isLoaded(np)) {
            return null;
        }
        BlockEntity be = level.getBlockEntity(np);
        if (be == null || be.isRemoved()) {
            return null;
        }
        return be.getCapability(cap, d.getOpposite()).resolve().orElse(null);
    }

    @Override
    public ItemStore neighborItems(LocalFace face) {
        var h = neighborCap(face, ForgeCapabilities.ITEM_HANDLER);
        return h == null ? null : new ForgeHandlers.ExternalItems(h, stray -> Containers.dropItemStack(level,
                pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5, stray));
    }

    @Override
    public FluidStore neighborFluids(LocalFace face) {
        var h = neighborCap(face, ForgeCapabilities.FLUID_HANDLER);
        return h == null ? null : new ForgeHandlers.ExternalFluids(h);
    }

    @Override
    public EnergyStore neighborEnergy(LocalFace face) {
        var h = neighborCap(face, ForgeCapabilities.ENERGY);
        return h == null ? null : new ForgeHandlers.ExternalEnergy(h);
    }

    @Override
    public int redstoneInput(LocalFace face) {
        Direction d = dir(face);
        BlockPos np = pos.relative(d);
        if (!level.isLoaded(np)) {
            return 0;
        }
        return level.getSignal(np, d);
    }

    @Override
    public TagLookup tags() {
        return McTagLookup.INSTANCE;
    }

    @Override
    public RecipeSource recipes() {
        return RecipeBridge.forLevel(level);
    }

    @Override
    public int fuelBurnTicks(ItemVariant v) {
        ItemStack s = McItemVariant.toStack(v, 1);
        return s.isEmpty() ? 0 : ForgeHooks.getBurnTime(s, null);
    }

    @Override
    public ItemVariant fuelRemainder(ItemVariant v) {
        ItemStack s = McItemVariant.toStack(v, 1);
        return s.hasCraftingRemainingItem() ? McItemVariant.of(s.getCraftingRemainingItem()) : null;
    }

    @Override
    public boolean canSeeSky(LocalFace face) {
        return level.canSeeSky(pos.relative(dir(face)));
    }

    @Override
    public boolean isDaytime() {
        return level.isDay();
    }

    @Override
    public boolean isRaining() {
        return level.isRainingAt(pos.above());
    }

    @Override
    public boolean isThundering() {
        return level.isThundering();
    }

    @Override
    public int[] durability(ItemVariant v) {
        net.minecraft.world.item.ItemStack st = dev.fieldframe.forge.adapter.McItemVariant.toStack(v, 1);
        return st.isEmpty() || !st.isDamageableItem() ? null : new int[]{st.getDamageValue(), st.getMaxDamage()};
    }

    @Override
    public ItemVariant withDamage(ItemVariant v, int damage) {
        net.minecraft.world.item.ItemStack st = dev.fieldframe.forge.adapter.McItemVariant.toStack(v, 1);
        if (st.isEmpty() || !st.isDamageableItem()) {
            return null;
        }
        st.setDamageValue(Math.max(0, Math.min(st.getMaxDamage() - 1, damage)));
        return dev.fieldframe.forge.adapter.McItemVariant.of(st);
    }

    /** Common-tag folders the Tag Unifier works on. */
    private static final java.util.List<String> UNIFY = java.util.List.of("ingots/", "nuggets/", "dusts/", "plates/", "gears/", "gems/",
            "raw_materials/", "storage_blocks/", "rods/");

    @Override
    public ItemVariant unified(ItemVariant v, java.util.List<String> preferred) {
        net.minecraft.world.item.ItemStack st = dev.fieldframe.forge.adapter.McItemVariant.toStack(v, 1);
        if (st.isEmpty() || !dev.fieldframe.forge.adapter.McItemVariant.of(new net.minecraft.world.item.ItemStack(st.getItem())).equals(v)) {
            return null;
        }
        for (var tag : st.getTags().toList()) {
            String ns = tag.location().getNamespace();
            String path = tag.location().getPath();
            if (!(ns.equals("c") || ns.equals("forge")) || UNIFY.stream().noneMatch(path::startsWith)) {
                continue;
            }
            for (String want : preferred) {
                for (var h : net.minecraft.core.registries.BuiltInRegistries.ITEM.getTagOrEmpty(tag)) {
                    if (net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(h.value()).getNamespace().equals(want)) {
                        return dev.fieldframe.forge.adapter.McItemVariant.of(new net.minecraft.world.item.ItemStack(h.value()));
                    }
                }
            }
            return null;
        }
        return null;
    }

    @Override
    public int moonPhase() {
        return level.getMoonPhase();
    }

    @Override
    public dev.fieldframe.api.world.WorldPos position() {
        return new dev.fieldframe.api.world.WorldPos(level.dimension().location().toString(), pos.getX(), pos.getY(), pos.getZ());
    }

    @Override
    public int skyDarkening() {
        return level.getSkyDarken();
    }

    @Override
    public String displayName(ItemVariant v) {
        ItemStack s = McItemVariant.toStack(v, 1);
        return s.isEmpty() ? v.id().toString() : s.getHoverName().getString();
    }
}
