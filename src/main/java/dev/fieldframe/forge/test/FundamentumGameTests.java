package dev.fieldframe.forge.test;

import dev.fieldframe.forge.Fieldframe;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraftforge.gametest.GameTestHolder;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Fieldframe gear checks run with every GameTest run. */
@GameTestHolder(Fieldframe.MOD_ID)
public final class FundamentumGameTests {
    private FundamentumGameTests() {
    }

    @GameTestGenerator
    public static Collection<TestFunction> generate() {
        List<TestFunction> out = new ArrayList<>();
        out.add(new TestFunction("defaultBatch", "fieldframe.electric_whips_pay_per_swing_and_charge", "fieldframe:empty7", 100, 0L, true,
                h -> dev.fieldframe.forge.compat.fundamentum.WhipGameTests.whipsPayPerSwingAndCharge(h)));
        out.add(new TestFunction("defaultBatch", "fieldframe.graviton_hook_is_a_fundamentum_hook", "fieldframe:empty7", 20, 0L, true,
                h -> dev.fieldframe.forge.compat.fundamentum.WhipGameTests.gravitonHookIsAFundamentumHook(h)));
        out.add(new TestFunction("defaultBatch", "fieldframe.gear_recipes_keep_upgrade_state", "fieldframe:empty7", 20, 0L, true,
                h -> dev.fieldframe.forge.compat.fundamentum.WhipGameTests.gearRecipesKeepUpgradeState(h)));
        return out;
    }
}
