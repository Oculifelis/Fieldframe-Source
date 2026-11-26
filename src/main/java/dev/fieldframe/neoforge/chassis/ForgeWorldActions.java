package dev.fieldframe.neoforge.chassis;

import com.mojang.authlib.GameProfile;
import dev.fieldframe.api.face.LocalFace;
import dev.fieldframe.api.face.Orientation;
import dev.fieldframe.api.id.FfId;
import dev.fieldframe.api.resource.FluidVariant;
import dev.fieldframe.api.resource.ItemStore;
import dev.fieldframe.api.resource.ItemVariant;
import dev.fieldframe.api.world.WorkGeometry;
import dev.fieldframe.api.world.WorkTarget;
import dev.fieldframe.api.world.WorldActions;
import dev.fieldframe.neoforge.adapter.Dirs;
import dev.fieldframe.neoforge.adapter.McFluidVariant;
import dev.fieldframe.neoforge.adapter.McItemVariant;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BucketPickup;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.CommonHooks;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.fluids.FluidStack;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * NeoForge 26.1.2 {@link WorldActions}. Every action runs as a {@link FakePlayer} whose UUID is the chassis
 * owner's (or a fixed Fieldframe UUID when the owner is unknown), so claim/protection mods that key on UUIDs
 * see the owner. Vanilla spawn protection and world border ({@code mayInteract}) and NeoForge break, place and
 * right-click events are honoured; a cancelled event changes nothing and reports PROTECTED.
 */
public final class ForgeWorldActions implements WorldActions {
    private static final UUID ANONYMOUS = UUID.nameUUIDFromBytes("fieldframe:anonymous".getBytes(StandardCharsets.UTF_8));
    private static final int MAX_ENTITY_SCAN = 32;

    private final ServerLevel level;
    private final BlockPos pos;
    private final Orientation orientation;
    private final UUID ownerId;
    private final String ownerName;
    private FakePlayer actor;

    public ForgeWorldActions(ServerLevel level, BlockPos pos, Orientation orientation, String ownerId, String ownerName) {
        this.level = level;
        this.pos = pos;
        this.orientation = orientation;
        UUID id = ANONYMOUS;
        try {
            if (ownerId != null && !ownerId.isEmpty()) {
                id = UUID.fromString(ownerId);
            }
        } catch (IllegalArgumentException ignored) {
            // malformed owner: act anonymously
        }
        this.ownerId = id;
        this.ownerName = ownerName == null || ownerName.isEmpty() ? "?" : ownerName;
    }

    private BlockPos at(WorkTarget t) {
        int[] o = WorkGeometry.offset(orientation, t);
        return pos.offset(o[0], o[1], o[2]);
    }

    private Direction normal(LocalFace face) {
        return Dirs.toMc(orientation.toWorld(face));
    }

    /** The acting fake player, positioned at the chassis and looking out through the face. */
    private FakePlayer actor(LocalFace face, ItemStack inHand) {
        if (actor == null) {
            actor = FakePlayerFactory.get(level, new GameProfile(ownerId, "[Fieldframe]"));
        }
        Direction d = normal(face);
        actor.setPos(pos.getX() + 0.5, pos.getY() + 0.5 - actor.getEyeHeight() + 0.5, pos.getZ() + 0.5);
        actor.setYRot(d.toYRot());
        actor.setXRot(d == Direction.DOWN ? 90 : d == Direction.UP ? -90 : 0);
        actor.getInventory().clearContent();
        actor.setItemInHand(InteractionHand.MAIN_HAND, inHand);
        return actor;
    }

    private void release() {
        if (actor != null) {
            actor.getInventory().clearContent();
        }
    }

    @Override
    public String actorName() {
        return "[Fieldframe] " + ownerName;
    }

    private static FfId fluidId(Fluid f) {
        return Dirs.id(BuiltInRegistries.FLUID.getKey(f));
    }

    private static Fluid sourceOf(Fluid f) {
        return f instanceof FlowingFluid ff ? ff.getSource() : f;
    }

    @Override
    public int temperature(WorkTarget t) {
        BlockPos p = at(t);
        if (!level.isLoaded(p)) {
            return -1;
        }
        BlockState st = level.getBlockState(p);
        FluidState fs = st.getFluidState();
        boolean lit = st.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.LIT)
                && st.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.LIT);
        dev.fieldframe.api.resource.FluidVariant fluid = fs.isEmpty() ? null : McFluidVariant.of(new FluidStack(sourceOf(fs.getType()), 1));
        int own = fs.isEmpty() ? -1 : fs.getFluidType().getTemperature();
        int ambient = dev.fieldframe.core.material.Temperatures.ambient(level.getBiome(p).value().getBaseTemperature());
        FfId block = st.isAir() ? null : Dirs.id(BuiltInRegistries.BLOCK.getKey(st.getBlock()));
        return dev.fieldframe.core.material.Temperatures.resolve(block, lit, fluid, own, ambient,
                dev.fieldframe.neoforge.adapter.McTagLookup.INSTANCE);
    }

    @Override
    public BlockInfo probe(WorkTarget t) {
        BlockPos p = at(t);
        if (!level.isLoaded(p)) {
            return BlockInfo.unloaded();
        }
        BlockState st = level.getBlockState(p);
        FluidState fs = st.getFluidState();
        boolean crop = st.getBlock() instanceof CropBlock;
        boolean mature = crop && ((CropBlock) st.getBlock()).isMaxAge(st);
        ItemVariant plant = null;
        if (crop) {
            ItemStack seed = st.getCloneItemStack(level, p, false);
            plant = seed.isEmpty() ? null : McItemVariant.of(seed);
        }
        return new BlockInfo(Outcome.OK, Dirs.id(BuiltInRegistries.BLOCK.getKey(st.getBlock())), st.isAir(),
                st.canBeReplaced(), st.getDestroySpeed(level, p), st.hasBlockEntity(), crop, mature, plant,
                fs.isEmpty() ? null : fluidId(sourceOf(fs.getType())), !fs.isEmpty() && fs.isSource(),
                !fs.isEmpty() && fs.is(FluidTags.WATER) && !fs.isSource());
    }

    @Override
    public BreakResult breakBlock(WorkTarget t, ItemVariant tool, boolean requireCorrectTool) {
        BlockPos p = at(t);
        if (!level.isLoaded(p)) {
            return BreakResult.fail(Outcome.UNLOADED, tool);
        }
        BlockState st = level.getBlockState(p);
        if (st.isAir() || st.getBlock() instanceof LiquidBlock) {
            return BreakResult.fail(Outcome.AIR, tool);
        }
        if (st.getDestroySpeed(level, p) < 0) {
            return BreakResult.fail(Outcome.UNBREAKABLE, tool);
        }
        ItemStack toolStack = tool == null ? ItemStack.EMPTY : McItemVariant.toStack(tool, 1);
        FakePlayer fp = actor(t.face(), toolStack);
        try {
            if (requireCorrectTool && st.requiresCorrectToolForDrops() && !fp.hasCorrectToolForDrops(st, level, p)) {
                return BreakResult.fail(Outcome.WRONG_TOOL, tool);
            }
            if (!level.mayInteract(fp, p)) {
                return BreakResult.fail(Outcome.PROTECTED, tool);
            }
            if (CommonHooks.fireBlockBreak(level, net.minecraft.world.level.GameType.SURVIVAL, fp, p, st).isCanceled()) {
                return BreakResult.fail(Outcome.PROTECTED, tool);
            }
            List<ItemStack> drops = Block.getDrops(st, level, p, level.getBlockEntity(p), fp, toolStack);
            if (!toolStack.isEmpty()) {
                toolStack.mineBlock(level, st, p, fp);
            }
            level.destroyBlock(p, false, fp);
            List<Drop> out = new ArrayList<>();
            for (ItemStack s : drops) {
                if (!s.isEmpty()) {
                    out.add(new Drop(McItemVariant.of(s), s.getCount()));
                }
            }
            ItemVariant after = tool == null || toolStack.isEmpty() ? null : McItemVariant.of(toolStack);
            return new BreakResult(Outcome.OK, out, after);
        } finally {
            release();
        }
    }

    @Override
    public Outcome place(WorkTarget t, ItemVariant item) {
        BlockPos p = at(t);
        if (!level.isLoaded(p)) {
            return Outcome.UNLOADED;
        }
        if (!level.getBlockState(p).canBeReplaced()) {
            return Outcome.NOT_REPLACEABLE;
        }
        ItemStack stack = McItemVariant.toStack(item, 1);
        if (!(stack.getItem() instanceof BlockItem bi)) {
            return Outcome.NOT_PLACEABLE;
        }
        FakePlayer fp = actor(t.face(), stack);
        try {
            if (!level.mayInteract(fp, p)) {
                return Outcome.PROTECTED;
            }
            Direction toChassis = normal(t.face()).getOpposite();
            BlockSnapshot snapshot = BlockSnapshot.create(level.dimension(), level, p);
            BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(p), toChassis, p, false);
            InteractionResult r = bi.place(new BlockPlaceContext(fp, InteractionHand.MAIN_HAND, stack, hit));
            if (!r.consumesAction()) {
                return Outcome.NOT_PLACEABLE;
            }
            if (EventHooks.onBlockPlace(fp, snapshot, toChassis)) {
                snapshot.restore(net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
                return Outcome.PROTECTED;
            }
            return Outcome.OK;
        } finally {
            release();
        }
    }

    @Override
    public UseResult use(WorkTarget t, ItemVariant held, int count) {
        BlockPos p = at(t);
        if (!level.isLoaded(p)) {
            return new UseResult(Outcome.UNLOADED, held, count, List.of());
        }
        ItemStack stack = held == null ? ItemStack.EMPTY : McItemVariant.toStack(held, count);
        FakePlayer fp = actor(t.face(), stack);
        try {
            if (!level.mayInteract(fp, p)) {
                return new UseResult(Outcome.PROTECTED, held, count, List.of());
            }
            Direction toChassis = normal(t.face()).getOpposite();
            BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(p), toChassis, p, false);
            PlayerInteractEvent.RightClickBlock ev = CommonHooks.onRightClickBlock(fp, InteractionHand.MAIN_HAND, p, hit);
            if (ev.isCanceled()) {
                return new UseResult(Outcome.PROTECTED, held, count, List.of());
            }
            boolean done = false;
            BlockState st = level.getBlockState(p);
            UseOnContext use = new UseOnContext(fp, InteractionHand.MAIN_HAND, hit);
            if (!ev.getUseItem().isFalse() && !stack.isEmpty()) {
                done = stack.onItemUseFirst(use).consumesAction();
            }
            if (!done && !ev.getUseBlock().isFalse()) {
                InteractionResult r = st.useItemOn(fp.getMainHandItem(), level, fp, InteractionHand.MAIN_HAND, hit);
                done = r.consumesAction();
                if (!done && r instanceof InteractionResult.TryEmptyHandInteraction) {
                    done = st.useWithoutItem(level, fp, hit).consumesAction();
                }
            }
            if (!done && !ev.getUseItem().isFalse() && !stack.isEmpty()) {
                done = bucket(fp, stack, p, st, hit) || fp.getMainHandItem().useOn(use).consumesAction();
            }
            ItemStack after = fp.getMainHandItem();
            List<Drop> extra = new ArrayList<>();
            var inv = fp.getInventory();
            for (int i = 0; i < inv.getContainerSize(); i++) {
                ItemStack s = inv.getItem(i);
                if (!s.isEmpty() && s != after) {
                    extra.add(new Drop(McItemVariant.of(s), s.getCount()));
                }
            }
            if (!done) {
                return new UseResult(Outcome.NOTHING_TO_DO, held, count, List.of());
            }
            return new UseResult(Outcome.OK, after.isEmpty() ? null : McItemVariant.of(after), after.getCount(), extra);
        } finally {
            release();
        }
    }

    /** Buckets pick up / empty fluids by ray-casting in vanilla; here they act on the target block directly. */
    private boolean bucket(FakePlayer fp, ItemStack stack, BlockPos p, BlockState st, BlockHitResult hit) {
        if (stack.is(Items.BUCKET) && st.getBlock() instanceof BucketPickup pickup && st.getFluidState().isSource()) {
            ItemStack filled = pickup.pickupBlock(fp, level, p, st);
            if (filled.isEmpty()) {
                return false;
            }
            stack.shrink(1);
            fp.setItemInHand(InteractionHand.MAIN_HAND, stack);
            if (!fp.getInventory().add(filled)) {
                fp.getInventory().setItem(fp.getInventory().getContainerSize() - 1, filled);
            }
            return true;
        }
        if (stack.getItem() instanceof BucketItem bi && bi.content != net.minecraft.world.level.material.Fluids.EMPTY
                && bi.emptyContents(fp, level, p, hit, stack)) {
            fp.setItemInHand(InteractionHand.MAIN_HAND, BucketItem.getEmptySuccessItem(stack, fp));
            return true;
        }
        return false;
    }

    @Override
    public FluidVariant drainSource(WorkTarget t, boolean simulate) {
        BlockPos p = at(t);
        if (!level.isLoaded(p)) {
            return null;
        }
        BlockState st = level.getBlockState(p);
        FluidState fs = st.getFluidState();
        if (fs.isEmpty() || !fs.isSource() || !(st.getBlock() instanceof BucketPickup pickup)) {
            return null;
        }
        FluidVariant v = McFluidVariant.of(new FluidStack(sourceOf(fs.getType()), 1000));
        if (simulate) {
            return v;
        }
        FakePlayer fp = actor(t.face(), ItemStack.EMPTY);
        try {
            if (!level.mayInteract(fp, p)
                    || CommonHooks.fireBlockBreak(level, net.minecraft.world.level.GameType.SURVIVAL, fp, p, st).isCanceled()) {
                return null;
            }
            return pickup.pickupBlock(fp, level, p, st).isEmpty() ? null : v;
        } finally {
            release();
        }
    }

    @Override
    public Outcome placeFluid(WorkTarget t, FluidVariant fluid) {
        BlockPos p = at(t);
        if (!level.isLoaded(p)) {
            return Outcome.UNLOADED;
        }
        BlockState st = level.getBlockState(p);
        if (!st.canBeReplaced() || !st.getFluidState().isEmpty()) {
            return Outcome.NOT_REPLACEABLE;
        }
        Fluid f = McFluidVariant.toStack(fluid, 1000).getFluid();
        if (!(f instanceof FlowingFluid ff)) {
            return Outcome.NOT_PLACEABLE;
        }
        BlockState block = ff.getSource(false).createLegacyBlock();
        if (block.isAir() || f.getFluidType().isVaporizedOnPlacement(level, p, McFluidVariant.toStack(fluid, 1000))) {
            return Outcome.NOT_PLACEABLE;
        }
        FakePlayer fp = actor(t.face(), ItemStack.EMPTY);
        try {
            if (!level.mayInteract(fp, p)) {
                return Outcome.PROTECTED;
            }
            BlockSnapshot snapshot = BlockSnapshot.create(level.dimension(), level, p);
            level.setBlock(p, block, 11);
            if (EventHooks.onBlockPlace(fp, snapshot, Direction.UP)) {
                snapshot.restore(net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
                return Outcome.PROTECTED;
            }
            return Outcome.OK;
        } finally {
            release();
        }
    }

    /** The box in front of a face: {@code range} deep, {@code 2*range-1} wide. */
    private AABB box(LocalFace face, int range) {
        Direction d = normal(face);
        int r = Math.max(0, range - 1);
        AABB b = new AABB(pos.relative(d)).expandTowards(d.getStepX() * r, d.getStepY() * r, d.getStepZ() * r);
        return b.inflate(d.getAxis() == Direction.Axis.X ? 0 : r, d.getAxis() == Direction.Axis.Y ? 0 : r,
                d.getAxis() == Direction.Axis.Z ? 0 : r);
    }

    @Override
    public List<Drop> fishingLoot(LocalFace face, int luck) {
        BlockPos water = pos.relative(normal(face));
        if (!level.isLoaded(water) || level.getServer() == null) {
            return List.of();
        }
        var params = new net.minecraft.world.level.storage.loot.LootParams.Builder(level)
                .withParameter(net.minecraft.world.level.storage.loot.parameters.LootContextParams.ORIGIN, Vec3.atCenterOf(water))
                .withParameter(net.minecraft.world.level.storage.loot.parameters.LootContextParams.TOOL, new ItemStack(Items.FISHING_ROD))
                .withLuck(luck)
                .create(net.minecraft.world.level.storage.loot.parameters.LootContextParamSets.FISHING);
        var table = level.getServer().reloadableRegistries().getLootTable(net.minecraft.world.level.storage.loot.BuiltInLootTables.FISHING);
        List<Drop> out = new ArrayList<>();
        for (ItemStack s : table.getRandomItems(params)) {
            if (!s.isEmpty()) {
                out.add(new Drop(McItemVariant.of(s), s.getCount()));
            }
        }
        return out;
    }

    @Override
    public int applyEffect(int range, FfId effect, int ticks, int amplifier) {
        var holder = BuiltInRegistries.MOB_EFFECT.get(net.minecraft.resources.Identifier.fromNamespaceAndPath(effect.namespace(), effect.path()));
        if (holder.isEmpty()) {
            return 0;
        }
        int n = 0;
        for (Player p : level.getEntitiesOfClass(Player.class, new AABB(pos).inflate(range), Player::isAlive)) {
            p.addEffect(new net.minecraft.world.effect.MobEffectInstance(holder.get(), ticks, amplifier, true, true));
            n++;
        }
        return n;
    }

    @Override
    public int collectExperience(LocalFace face, int range, int max) {
        int taken = 0;
        for (var orb : level.getEntitiesOfClass(net.minecraft.world.entity.ExperienceOrb.class, box(face, range), Entity::isAlive)) {
            // Merged orbs count several times; read the count from the orb's own saved data (no private access).
            var out = net.minecraft.world.level.storage.TagValueOutput.createWithoutContext(net.minecraft.util.ProblemReporter.DISCARDING);
            orb.saveWithoutId(out);
            int points = orb.getValue() * Math.max(1, out.buildResult().getIntOr("Count", 1));
            if (taken + points > max) {
                continue;
            }
            taken += points;
            orb.discard();
        }
        return taken;
    }

    @Override
    public int collectItems(LocalFace face, int range, ItemStore into, int max) {
        int total = 0;
        int scanned = 0;
        for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, box(face, range), Entity::isAlive)) {
            if (total >= max || scanned++ >= MAX_ENTITY_SCAN) {
                break;
            }
            // Items reserved for a specific player (e.g. given or dropped on death) are left for them.
            if (e.getTarget() != null) {
                continue;
            }
            ItemStack s = e.getItem();
            int in = into.insert(McItemVariant.of(s), Math.min(s.getCount(), max - total), false);
            if (in > 0) {
                total += in;
                ItemStack rest = s.copy();
                rest.shrink(in);
                if (rest.isEmpty()) {
                    e.discard();
                } else {
                    e.setItem(rest);
                }
            }
        }
        return total;
    }

    @Override
    public Outcome dropItem(LocalFace face, ItemVariant v, int count) {
        Direction d = normal(face);
        BlockPos a = pos.relative(d);
        if (!level.isLoaded(a)) {
            return Outcome.UNLOADED;
        }
        if (level.getBlockState(a).isCollisionShapeFullBlock(level, a)) {
            return Outcome.BLOCKED;
        }
        ItemStack s = McItemVariant.toStack(v, count);
        if (s.isEmpty()) {
            return Outcome.NOT_PLACEABLE;
        }
        ItemEntity e = new ItemEntity(level, a.getX() + 0.5, a.getY() + (d == Direction.DOWN ? 0.6 : 0.2), a.getZ() + 0.5, s);
        e.setDeltaMovement(d.getStepX() * 0.1, d.getStepY() * 0.1, d.getStepZ() * 0.1);
        e.setDefaultPickUpDelay();
        return level.addFreshEntity(e) ? Outcome.OK : Outcome.BLOCKED;
    }

    @Override
    public int moveEntities(LocalFace face, int range, double strength, boolean pull, boolean includePlayers, int maxEntities) {
        Direction d = normal(face);
        double s = pull ? -strength : strength;
        int n = 0;
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, box(face, range),
                x -> x.isAlive() && !x.isSpectator() && (includePlayers || !(x instanceof Player)))) {
            if (n >= maxEntities) {
                break;
            }
            if (e instanceof Player pl && (pl.getAbilities().flying || pl.isCreative())) {
                continue;
            }
            e.push(d.getStepX() * s, d.getStepY() * s, d.getStepZ() * s);
            e.hurtMarked = true;
            n++;
        }
        return n;
    }

    @Override
    public AttackResult attackMobs(LocalFace face, int range, ItemVariant weapon, boolean named, int maxTargets) {
        List<LivingEntity> targets = level.getEntitiesOfClass(LivingEntity.class, box(face, range), e -> e.isAlive() && !(e instanceof Player)
                && !(e instanceof net.minecraft.world.entity.decoration.ArmorStand)
                && !(e instanceof net.minecraft.world.entity.TamableAnimal t && t.isTame())
                && (named || !e.hasCustomName()));
        if (targets.isEmpty()) {
            return new AttackResult(Outcome.NOTHING_TO_DO, 0, 0, weapon);
        }
        GrinderPlayer gp = GrinderPlayer.get(level, ownerId);
        Direction d = normal(face);
        gp.setPos(pos.getX() + 0.5 + d.getStepX() * 0.6, pos.getY() + 0.1, pos.getZ() + 0.5 + d.getStepZ() * 0.6);
        gp.setYRot(d.toYRot());
        ItemStack stack = weapon == null ? ItemStack.EMPTY : McItemVariant.toStack(weapon, 1);
        gp.getInventory().clearContent();
        gp.setItemInHand(InteractionHand.MAIN_HAND, stack);
        gp.weaponModifiers(stack, true);
        int hits = 0;
        int kills = 0;
        try {
            for (LivingEntity e : targets.subList(0, Math.min(maxTargets, targets.size()))) {
                gp.charge();
                net.minecraft.world.phys.Vec3 motion = e.getDeltaMovement();
                gp.attack(e);
                // A grinder holds its mobs: no knockback out of reach.
                e.setDeltaMovement(motion);
                hits++;
                if (!e.isAlive()) {
                    kills++;
                }
                if (!stack.isEmpty() && gp.getMainHandItem().isEmpty()) {
                    break;
                }
            }
        } finally {
            gp.weaponModifiers(stack, false);
        }
        ItemStack after = gp.getMainHandItem();
        gp.getInventory().clearContent();
        return new AttackResult(Outcome.OK, hits, kills, after.isEmpty() ? null : McItemVariant.of(after));
    }

    @Override
    public WalkingAnimal animalOnTreadmill(LocalFace face) {
        BlockPos at = at(new WorkTarget(face, 1, 0, 0));
        if (!level.isLoaded(at)) {
            return null;
        }
        List<Animal> list = level.getEntitiesOfClass(Animal.class, new net.minecraft.world.phys.AABB(at).inflate(0.2), e -> e.isAlive());
        if (list.isEmpty()) {
            return null;
        }
        Animal a = list.get(0);
        // Walks in place: no wandering off the belt.
        a.getNavigation().stop();
        double cx = at.getX() + 0.5;
        double cz = at.getZ() + 0.5;
        if (Math.abs(a.getX() - cx) > 0.25 || Math.abs(a.getZ() - cz) > 0.25) {
            a.teleportTo(cx, a.getY(), cz);
        }
        a.setDeltaMovement(0, a.getDeltaMovement().y, 0);
        dev.fieldframe.api.id.FfId type = dev.fieldframe.api.id.FfId.parse(
                net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(a.getType()).toString());
        return new WalkingAnimal(type, a.isBaby(), a.getAttributeBaseValue(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED));
    }

    @Override
    public int countEntities(LocalFace face, int range, String kind) {
        List<Entity> list = level.getEntities((Entity) null, box(face, range), e -> e.isAlive() && !e.isSpectator() && switch (kind) {
            case "player" -> e instanceof Player;
            case "mob" -> e instanceof Enemy;
            case "animal" -> e instanceof Animal;
            case "item" -> e instanceof ItemEntity;
            default -> true;
        });
        return Math.min(64, list.size());
    }

    @Override
    public EntitySample sampleMotion(LocalFace face, int range, int maxEntities) {
        int n = 0;
        double speed = 0;
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, box(face, range), Entity::isAlive)) {
            if (n >= maxEntities) {
                break;
            }
            double dx = e.getX() - e.xo;
            double dz = e.getZ() - e.zo;
            // Per-entity cap: teleports and knockback do not count as more than a sprint.
            speed += Math.min(0.4, Math.sqrt(dx * dx + dz * dz));
            n++;
        }
        return new EntitySample(n, speed);
    }

    @Override
    public void mark(WorkTarget t) {
        BlockPos p = at(t);
        if (level.isLoaded(p)) {
            level.sendParticles(ParticleTypes.HAPPY_VILLAGER, p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5, 1, 0, 0, 0, 0);
        }
    }
}
