package dev.fieldframe.neoforge.item;

import dev.fieldframe.api.FieldframeRegistry;
import dev.fieldframe.api.data.FfData;
import dev.fieldframe.api.id.FfId;
import dev.fieldframe.api.module.ModuleDefinition;
import dev.fieldframe.core.chassis.InstalledModule;
import dev.fieldframe.neoforge.adapter.NbtBridge;
import dev.fieldframe.neoforge.registry.FfContent;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

/**
 * One item per registered module definition. The stack may carry the module's last configuration so that
 * removing and reinstalling a module keeps its settings (configuration only: never contents or ownership).
 */
public final class ModuleItem extends Item {
    private final FfId moduleId;

    public ModuleItem(FfId moduleId, Properties props) {
        super(props);
        this.moduleId = moduleId;
    }

    public FfId moduleId() {
        return moduleId;
    }


    public static ItemStack stackFor(InstalledModule m) {
        ModuleItem item = FfContent.moduleItem(m.moduleId());
        if (item == null) {
            return ItemStack.EMPTY;
        }
        ItemStack s = new ItemStack(item);
        if (!m.isMissing()) {
            FfData cfg = m.config();
            if (!cfg.equals(m.definition().defaultConfig())) {
                CompoundTag t = new CompoundTag();
                t.put("FieldframeConfig", NbtBridge.toTag(cfg));
                s.set(DataComponents.CUSTOM_DATA, CustomData.of(t));
            }
        }
        return s;
    }

    /** Stored configuration or null (the chassis sanitizes it against the schema on install). */
    public static FfData storedConfig(ItemStack s) {
        CustomData cd = s.get(DataComponents.CUSTOM_DATA);
        if (cd == null) {
            return null;
        }
        CompoundTag t = cd.copyTag();
        return t.getCompound("FieldframeConfig").map(NbtBridge::fromTag).orElse(null);
    }

    @Override
    public Component getName(ItemStack stack) {
        ModuleDefinition def = FieldframeRegistry.module(moduleId);
        if (def == null) {
            return Component.literal(moduleId.toString());
        }
        return Component.translatableWithFallback(def.translationKey(), def.presentation().getString("display_name", moduleId.toString()));
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, net.minecraft.world.item.component.TooltipDisplay display, java.util.function.Consumer<Component> tip, TooltipFlag flag) {
        ModuleDefinition def = FieldframeRegistry.module(moduleId);
        if (def == null) {
            tip.accept(Component.translatable("fieldframe.tooltip.missing_definition").withStyle(ChatFormatting.RED));
            return;
        }
        tip.accept(Component.translatable("fieldframe.category." + def.category().key()).withStyle(ChatFormatting.GRAY));
        tip.accept(Component.translatable(def.translationKey() + ".desc").withStyle(ChatFormatting.DARK_GRAY));
        if (FieldframeRegistry.isDisabled(moduleId)) {
            tip.accept(Component.translatable("fieldframe.tooltip.disabled_by_pack").withStyle(ChatFormatting.RED));
        }
        if (storedConfig(stack) != null) {
            tip.accept(Component.translatable("fieldframe.tooltip.configured").withStyle(ChatFormatting.AQUA));
        }
        if (flag.isAdvanced()) {
            tip.accept(Component.literal(moduleId + " (" + FieldframeRegistry.source(moduleId) + ")").withStyle(ChatFormatting.DARK_GRAY));
        }
    }
}
