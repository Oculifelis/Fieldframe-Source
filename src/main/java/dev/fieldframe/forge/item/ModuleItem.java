package dev.fieldframe.forge.item;

import dev.fieldframe.api.FieldframeRegistry;
import dev.fieldframe.api.data.FfData;
import dev.fieldframe.api.id.FfId;
import dev.fieldframe.api.module.ModuleDefinition;
import dev.fieldframe.core.chassis.InstalledModule;
import dev.fieldframe.forge.adapter.NbtBridge;
import dev.fieldframe.forge.registry.FfContent;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

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

    @Override
    public String getDescriptionId() {
        ModuleDefinition def = FieldframeRegistry.module(moduleId);
        return def != null ? def.translationKey() : super.getDescriptionId();
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
                s.getOrCreateTag().put("FieldframeConfig", NbtBridge.toTag(cfg));
            }
        }
        return s;
    }

    /** Stored configuration or null (the chassis sanitizes it against the schema on install). */
    public static FfData storedConfig(ItemStack s) {
        return s.hasTag() && s.getTag().contains("FieldframeConfig") ? NbtBridge.fromTag(s.getTag().getCompound("FieldframeConfig")) : null;
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> tip, TooltipFlag flag) {
        ModuleDefinition def = FieldframeRegistry.module(moduleId);
        if (def == null) {
            tip.add(Component.translatable("fieldframe.tooltip.missing_definition").withStyle(ChatFormatting.RED));
            return;
        }
        tip.add(Component.translatable("fieldframe.category." + def.category().key()).withStyle(ChatFormatting.GRAY));
        tip.add(Component.translatable(def.translationKey() + ".desc").withStyle(ChatFormatting.DARK_GRAY));
        if (FieldframeRegistry.isDisabled(moduleId)) {
            tip.add(Component.translatable("fieldframe.tooltip.disabled_by_pack").withStyle(ChatFormatting.RED));
        }
        if (storedConfig(stack) != null) {
            tip.add(Component.translatable("fieldframe.tooltip.configured").withStyle(ChatFormatting.AQUA));
        }
        if (flag.isAdvanced()) {
            tip.add(Component.literal(moduleId + " (" + FieldframeRegistry.source(moduleId) + ")").withStyle(ChatFormatting.DARK_GRAY));
        }
    }
}
