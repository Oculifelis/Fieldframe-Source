package dev.fieldframe.forge.chassis;

import dev.fieldframe.api.face.LocalFace;
import dev.fieldframe.api.face.Orientation;
import dev.fieldframe.api.face.WorldDir;
import dev.fieldframe.api.module.ChassisWorld;
import dev.fieldframe.api.process.RecipeSource;
import dev.fieldframe.api.resource.EnergyStore;
import dev.fieldframe.api.resource.FluidStore;
import dev.fieldframe.api.resource.ItemStore;
import dev.fieldframe.api.resource.ItemVariant;
import dev.fieldframe.api.resource.TagLookup;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;

import java.util.List;

/**
 * The world as a Field Kit sees it: the carrier's level, weather, time and sky, but no neighbours, no redstone and no
 * world actions, so a kit never reaches the blocks around its carrier. Item services (recipes, charging, repairs)
 * come from {@link ForgeChassisWorld}.
 */
public final class KitWorld implements ChassisWorld {
    private final ServerLevel level;
    private final BlockPos pos;
    private final ForgeChassisWorld base;

    public KitWorld(ServerLevel level, Player carrier) {
        this.level = level;
        this.pos = BlockPos.containing(carrier.getEyePosition());
        this.base = new ForgeChassisWorld(level, pos, new Orientation(WorldDir.NORTH), carrier.getUUID().toString(), carrier.getName().getString());
    }

    @Override
    public long gameTime() {
        return level.getGameTime();
    }

    @Override
    public boolean isNeighborLoaded(LocalFace face) {
        return true;
    }

    @Override
    public ItemStore neighborItems(LocalFace face) {
        return null;
    }

    @Override
    public FluidStore neighborFluids(LocalFace face) {
        return null;
    }

    @Override
    public EnergyStore neighborEnergy(LocalFace face) {
        return null;
    }

    @Override
    public int redstoneInput(LocalFace face) {
        return 0;
    }

    @Override
    public TagLookup tags() {
        return base.tags();
    }

    @Override
    public RecipeSource recipes() {
        return base.recipes();
    }

    @Override
    public int fuelBurnTicks(ItemVariant v) {
        return base.fuelBurnTicks(v);
    }

    @Override
    public ItemVariant fuelRemainder(ItemVariant v) {
        return base.fuelRemainder(v);
    }

    /** Every face sees what the carrier's head sees. */
    @Override
    public boolean canSeeSky(LocalFace face) {
        return level.canSeeSky(pos);
    }

    @Override
    public boolean isDaytime() {
        return base.isDaytime();
    }

    @Override
    public boolean isRaining() {
        return base.isRaining();
    }

    @Override
    public boolean isThundering() {
        return base.isThundering();
    }

    @Override
    public int skyDarkening() {
        return base.skyDarkening();
    }

    @Override
    public int[] durability(ItemVariant v) {
        return base.durability(v);
    }

    @Override
    public ItemVariant withDamage(ItemVariant v, int damage) {
        return base.withDamage(v, damage);
    }

    @Override
    public ItemVariant unified(ItemVariant v, List<String> preferredNamespaces) {
        return base.unified(v, preferredNamespaces);
    }

    @Override
    public int moonPhase() {
        return base.moonPhase();
    }

    @Override
    public dev.fieldframe.api.world.WorldPos position() {
        return base.position();
    }

    @Override
    public dev.fieldframe.api.world.WorldActions actions() {
        return null;
    }

    @Override
    public CraftResult craft(List<ItemVariant> grid) {
        return base.craft(grid);
    }

    @Override
    public dev.fieldframe.api.data.FfData itemData(ItemVariant v, String key) {
        return base.itemData(v, key);
    }

    @Override
    public ItemVariant withItemData(ItemVariant v, String key, dev.fieldframe.api.data.FfData data) {
        return base.withItemData(v, key, data);
    }

    @Override
    public ItemVariant itemVariant(dev.fieldframe.api.id.FfId id) {
        return base.itemVariant(id);
    }

    @Override
    public dev.fieldframe.api.resource.FluidVariant fluidVariant(dev.fieldframe.api.id.FfId id) {
        return base.fluidVariant(id);
    }

    @Override
    public ItemVariant brew(ItemVariant b, ItemVariant ingredient) {
        return base.brew(b, ingredient);
    }

    @Override
    public ItemVariant enchant(ItemVariant item, int lvl, long seed) {
        return base.enchant(item, lvl, seed);
    }

    @Override
    public int foodValue(ItemVariant v) {
        return base.foodValue(v);
    }

    @Override
    public int enchantmentLevels(ItemVariant v) {
        return base.enchantmentLevels(v);
    }

    @Override
    public ChargeResult charge(ItemVariant v, long maxRf) {
        return base.charge(v, maxRf);
    }

    @Override
    public String displayName(ItemVariant v) {
        return base.displayName(v);
    }
}
