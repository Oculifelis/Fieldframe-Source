package dev.fieldframe.neoforge.chassis;

import dev.fieldframe.api.face.LocalFace;
import dev.fieldframe.api.face.Orientation;
import dev.fieldframe.api.module.ChassisWorld;
import dev.fieldframe.api.process.RecipeSource;
import dev.fieldframe.api.resource.EnergyStore;
import dev.fieldframe.api.resource.FluidStore;
import dev.fieldframe.api.resource.ItemStore;
import dev.fieldframe.api.resource.ItemVariant;
import dev.fieldframe.api.resource.TagLookup;
import dev.fieldframe.neoforge.adapter.Dirs;
import dev.fieldframe.neoforge.adapter.NeoHandlers;
import dev.fieldframe.neoforge.adapter.McItemVariant;
import dev.fieldframe.neoforge.adapter.McTagLookup;
import dev.fieldframe.neoforge.recipe.RecipeBridge;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.Containers;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.capabilities.Capabilities;

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
        net.minecraft.nbt.CompoundTag t = dev.fieldframe.neoforge.feature.ItemEnergy.data(McItemVariant.toStack(v, 1));
        return !t.contains(key) ? null : dev.fieldframe.neoforge.adapter.NbtBridge.fromTag(t.getCompoundOrEmpty(key));
    }

    @Override
    public ItemVariant withItemData(ItemVariant v, String key, dev.fieldframe.api.data.FfData data) {
        ItemStack s = McItemVariant.toStack(v, 1);
        if (s.isEmpty()) {
            return null;
        }
        dev.fieldframe.neoforge.feature.ItemEnergy.editData(s, t -> t.put(key, dev.fieldframe.neoforge.adapter.NbtBridge.toTag(data)));
        return McItemVariant.of(s);
    }

    @Override
    public ItemVariant itemVariant(dev.fieldframe.api.id.FfId id) {
        var item = net.minecraft.core.registries.BuiltInRegistries.ITEM.getOptional(
                net.minecraft.resources.Identifier.fromNamespaceAndPath(id.namespace(), id.path())).orElse(null);
        return item == null || item == net.minecraft.world.item.Items.AIR ? null
                : dev.fieldframe.neoforge.adapter.McItemVariant.of(new net.minecraft.world.item.ItemStack(item));
    }

    @Override
    public dev.fieldframe.api.resource.FluidVariant fluidVariant(dev.fieldframe.api.id.FfId id) {
        var fluid = net.minecraft.core.registries.BuiltInRegistries.FLUID.getOptional(
                net.minecraft.resources.Identifier.fromNamespaceAndPath(id.namespace(), id.path())).orElse(null);
        return fluid == null || fluid == net.minecraft.world.level.material.Fluids.EMPTY ? null
                : dev.fieldframe.neoforge.adapter.McFluidVariant.of(new net.neoforged.neoforge.fluids.FluidStack(fluid, 1));
    }

    @Override
    public ItemVariant brew(ItemVariant base, ItemVariant ingredient) {
        net.minecraft.world.item.ItemStack b = dev.fieldframe.neoforge.adapter.McItemVariant.toStack(base, 1);
        net.minecraft.world.item.ItemStack i = dev.fieldframe.neoforge.adapter.McItemVariant.toStack(ingredient, 1);
        if (b.isEmpty() || i.isEmpty() || !level.potionBrewing().hasMix(b, i)) {
            return null;
        }
        net.minecraft.world.item.ItemStack r = level.potionBrewing().mix(i, b);
        return r.isEmpty() || net.minecraft.world.item.ItemStack.isSameItemSameComponents(r, b) ? null
                : dev.fieldframe.neoforge.adapter.McItemVariant.of(r);
    }

    @Override
    public ItemVariant enchant(ItemVariant item, int cost, long seed) {
        net.minecraft.world.item.ItemStack s = dev.fieldframe.neoforge.adapter.McItemVariant.toStack(item, 1);
        if (s.isEmpty() || !s.isEnchantable()) {
            return null;
        }
        var set = level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT)
                .get(net.minecraft.tags.EnchantmentTags.IN_ENCHANTING_TABLE);
        net.minecraft.world.item.ItemStack r = net.minecraft.world.item.enchantment.EnchantmentHelper.enchantItem(
                net.minecraft.util.RandomSource.create(seed), s.copy(), cost, level.registryAccess(), set);
        return r.isEmpty() || !(r.isEnchanted() || r.is(net.minecraft.world.item.Items.ENCHANTED_BOOK)) ? null
                : dev.fieldframe.neoforge.adapter.McItemVariant.of(r);
    }

    @Override
    public int foodValue(ItemVariant v) {
        var food = dev.fieldframe.neoforge.adapter.McItemVariant.toStack(v, 1).get(net.minecraft.core.component.DataComponents.FOOD);
        return food == null ? 0 : Math.max(0, food.nutrition());
    }

    @Override
    public int enchantmentLevels(ItemVariant v) {
        net.minecraft.world.item.ItemStack s = dev.fieldframe.neoforge.adapter.McItemVariant.toStack(v, 1);
        var ench = net.minecraft.world.item.enchantment.EnchantmentHelper.getEnchantmentsForCrafting(s);
        int sum = 0;
        for (var e : ench.entrySet()) {
            sum += e.getIntValue();
        }
        return sum;
    }

    @Override
    public ChargeResult charge(ItemVariant v, long maxRf) {
        net.minecraft.world.item.ItemStack s = dev.fieldframe.neoforge.adapter.McItemVariant.toStack(v, 1);
        if (s.isEmpty()) {
            return null;
        }
        var handler = net.neoforged.neoforge.transfer.access.ItemAccess.forStack(s).getCapability(net.neoforged.neoforge.capabilities.Capabilities.Energy.ITEM);
        if (handler == null || handler.getCapacityAsLong() <= 0) {
            return null;
        }
        long accepted = new dev.fieldframe.neoforge.adapter.NeoHandlers.ExternalEnergy(handler).receive(maxRf, false);
        boolean full = handler.getAmountAsLong() >= handler.getCapacityAsLong();
        return new ChargeResult(dev.fieldframe.neoforge.adapter.McItemVariant.of(s), accepted, full);
    }

    @Override
    public CraftResult craft(java.util.List<ItemVariant> grid) {
        java.util.List<ItemStack> stacks = new java.util.ArrayList<>();
        for (int i = 0; i < 9; i++) {
            ItemVariant v = i < grid.size() ? grid.get(i) : null;
            stacks.add(v == null ? ItemStack.EMPTY : McItemVariant.toStack(v, 1));
        }
        var input = net.minecraft.world.item.crafting.CraftingInput.of(3, 3, stacks);
        var recipe = level.getServer().getRecipeManager().getRecipeFor(net.minecraft.world.item.crafting.RecipeType.CRAFTING, input, level);
        if (level.getServer() == null || recipe.isEmpty()) {
            return null;
        }
        ItemStack out = recipe.get().value().assemble(input);
        if (out.isEmpty()) {
            return null;
        }
        java.util.List<ItemVariant> rem = new java.util.ArrayList<>();
        for (ItemStack r : recipe.get().value().getRemainingItems(input)) {
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

    private <T> T neighborCap(LocalFace face, BlockCapability<T, Direction> cap) {
        Direction d = dir(face);
        BlockPos np = pos.relative(d);
        if (!level.isLoaded(np)) {
            return null;
        }
        return level.getCapability(cap, np, d.getOpposite());
    }

    @Override
    public ItemStore neighborItems(LocalFace face) {
        var h = neighborCap(face, Capabilities.Item.BLOCK);
        return h == null ? null : new NeoHandlers.ExternalItems(h);
    }

    @Override
    public FluidStore neighborFluids(LocalFace face) {
        var h = neighborCap(face, Capabilities.Fluid.BLOCK);
        return h == null ? null : new NeoHandlers.ExternalFluids(h);
    }

    @Override
    public EnergyStore neighborEnergy(LocalFace face) {
        var h = neighborCap(face, Capabilities.Energy.BLOCK);
        return h == null ? null : new NeoHandlers.ExternalEnergy(h);
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
        return s.isEmpty() ? 0 : level.fuelValues().burnDuration(s);
    }

    @Override
    public ItemVariant fuelRemainder(ItemVariant v) {
        ItemStack s = McItemVariant.toStack(v, 1);
        var rem = s.getItem().getCraftingRemainder(s);
        return rem == null ? null : McItemVariant.of(rem.create());
    }

    @Override
    public boolean canSeeSky(LocalFace face) {
        return level.canSeeSky(pos.relative(dir(face)));
    }

    @Override
    public boolean isDaytime() {
        return level.isBrightOutside();
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
        net.minecraft.world.item.ItemStack st = dev.fieldframe.neoforge.adapter.McItemVariant.toStack(v, 1);
        return st.isEmpty() || !st.isDamageableItem() ? null : new int[]{st.getDamageValue(), st.getMaxDamage()};
    }

    @Override
    public ItemVariant withDamage(ItemVariant v, int damage) {
        net.minecraft.world.item.ItemStack st = dev.fieldframe.neoforge.adapter.McItemVariant.toStack(v, 1);
        if (st.isEmpty() || !st.isDamageableItem()) {
            return null;
        }
        st.setDamageValue(Math.max(0, Math.min(st.getMaxDamage() - 1, damage)));
        return dev.fieldframe.neoforge.adapter.McItemVariant.of(st);
    }

    /** Common-tag folders the Tag Unifier works on. */
    private static final java.util.List<String> UNIFY = java.util.List.of("ingots/", "nuggets/", "dusts/", "plates/", "gears/", "gems/",
            "raw_materials/", "storage_blocks/", "rods/");

    @Override
    public ItemVariant unified(ItemVariant v, java.util.List<String> preferred) {
        net.minecraft.world.item.ItemStack st = dev.fieldframe.neoforge.adapter.McItemVariant.toStack(v, 1);
        if (st.isEmpty() || !dev.fieldframe.neoforge.adapter.McItemVariant.of(new net.minecraft.world.item.ItemStack(st.getItem())).equals(v)) {
            return null;
        }
        for (var tag : st.typeHolder().tags().toList()) {
            String ns = tag.location().getNamespace();
            String path = tag.location().getPath();
            if (!(ns.equals("c") || ns.equals("forge")) || UNIFY.stream().noneMatch(path::startsWith)) {
                continue;
            }
            for (String want : preferred) {
                for (var h : net.minecraft.core.registries.BuiltInRegistries.ITEM.getTagOrEmpty(tag)) {
                    if (net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(h.value()).getNamespace().equals(want)) {
                        return dev.fieldframe.neoforge.adapter.McItemVariant.of(new net.minecraft.world.item.ItemStack(h.value()));
                    }
                }
            }
            return null;
        }
        return null;
    }

    @Override
    public int moonPhase() {
        return level.environmentAttributes().getValue(net.minecraft.world.attribute.EnvironmentAttributes.MOON_PHASE, net.minecraft.world.phys.Vec3.atCenterOf(pos)).index();
    }

    @Override
    public dev.fieldframe.api.world.WorldPos position() {
        return new dev.fieldframe.api.world.WorldPos(level.dimension().identifier().toString(), pos.getX(), pos.getY(), pos.getZ());
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
