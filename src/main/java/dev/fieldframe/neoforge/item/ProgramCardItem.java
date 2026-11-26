package dev.fieldframe.neoforge.item;

import dev.fieldframe.api.data.FfData;
import dev.fieldframe.api.face.LocalFace;
import dev.fieldframe.core.chassis.ChassisCore;
import dev.fieldframe.core.chassis.InstalledModule;
import dev.fieldframe.core.modules.BuiltinModules;
import dev.fieldframe.neoforge.chassis.ChassisAccess;
import dev.fieldframe.neoforge.chassis.ChassisBlockEntity;
import dev.fieldframe.neoforge.feature.ItemEnergy;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import java.util.function.Consumer;

/**
 * Program Card: carries a Controller Plate program from one machine to another. Sneak-use on a Controller Plate's side
 * copies its program; use on another pastes it (owner or op only, validated like the machine screen's editor);
 * sneak-use in the air wipes the card. The chassis block hands the use to {@link #useOn}.
 */
public final class ProgramCardItem extends Item {
    private static final String KEY = "program";

    public ProgramCardItem(Properties props) {
        super(props);
    }

    public static String program(ItemStack stack) {
        return ItemEnergy.data(stack).getStringOr(KEY, "");
    }

    public static void setProgram(ItemStack stack, String program) {
        ItemEnergy.editData(stack, t -> {
            if (program.isEmpty()) {
                t.remove(KEY);
            } else {
                t.putString(KEY, program);
            }
        });
    }

    /** Server: the card used on {@code face} of a chassis. */
    public static void useOn(ChassisBlockEntity be, LocalFace face, Player player, ItemStack stack) {
        InstalledModule m = be.core().module(face);
        if (m == null || !BuiltinModules.CONTROLLER.equals(m.moduleId())) {
            tell(player, Component.translatable("item.fieldframe.program_card.not_controller"));
            return;
        }
        if (player.isShiftKeyDown()) {
            String program = m.config().getString(KEY, "");
            setProgram(stack, program);
            tell(player, Component.translatable("item.fieldframe.program_card.copied", lines(program)));
            return;
        }
        String program = program(stack);
        if (program.isEmpty()) {
            tell(player, Component.translatable("item.fieldframe.program_card.empty"));
            return;
        }
        if (!ChassisAccess.mayEdit(player, be.core())) {
            tell(player, Component.translatable("fieldframe.msg.not_owner", be.core().ownerName()));
            return;
        }
        ChassisCore.Result r = be.core().setConfig(face, new FfData().putString(KEY, program));
        if (r.ok()) {
            be.setChanged();
            tell(player, Component.translatable("item.fieldframe.program_card.pasted", lines(program)));
        } else {
            tell(player, Component.translatable("fieldframe.result." + r.code().toLowerCase(java.util.Locale.ROOT), face.key(), r.detail()));
        }
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (player.isShiftKeyDown() && !program(stack).isEmpty()) {
            if (!level.isClientSide()) {
                setProgram(stack, "");
                tell(player, Component.translatable("item.fieldframe.program_card.wiped"));
            }
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.PASS;
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return !program(stack).isEmpty();
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, net.minecraft.world.item.component.TooltipDisplay display, Consumer<Component> tip, TooltipFlag flag) {
        String program = program(stack);
        if (program.isEmpty()) {
            tip.accept(Component.translatable("item.fieldframe.program_card.desc_blank").withStyle(ChatFormatting.GRAY));
            return;
        }
        tip.accept(Component.translatable("item.fieldframe.program_card.desc_lines", lines(program)).withStyle(ChatFormatting.GRAY));
        String[] shown = program.split("\n", 4);
        for (int i = 0; i < Math.min(3, shown.length); i++) {
            String line = shown[i].length() > 40 ? shown[i].substring(0, 39) + "…" : shown[i];
            tip.accept(Component.literal("  " + line).withStyle(ChatFormatting.DARK_GRAY));
        }
    }

    static int lines(String program) {
        return program.isEmpty() ? 0 : (int) program.lines().count();
    }

    private static void tell(Player player, Component message) {
        if (player instanceof ServerPlayer sp) {
            sp.sendOverlayMessage(message);
        }
    }
}
