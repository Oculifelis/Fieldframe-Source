package dev.fieldframe.core.endertech;

import dev.fieldframe.api.resource.EnergyStore;

import java.util.List;
import java.util.function.IntSupplier;

/**
 * Charge Pad and Health Pad tick logic over platform-provided receivers. The platform lists what is in the box in
 * front of the pad; this class decides how much RF and health moves, so the rules are identical on every edition.
 */
public final class PadLogic {
    private PadLogic() {
    }

    /** A chargeable item (as an energy store) belonging to one entity in range. */
    public record Receiver(List<EnergyStore> items) {
    }

    /**
     * Sends up to {@code tier.padSendPerTick} RF from {@code buffer} (unlimited for Creative), split evenly between the
     * entities, each share going to that entity's items in order. Returns the RF actually sent.
     */
    public static long charge(EnderTechRules.Tier tier, EnergyStore buffer, List<Receiver> receivers) {
        if (receivers.isEmpty()) {
            return 0;
        }
        long sendable = tier.creative() ? tier.padSendPerTick : Math.min(tier.padSendPerTick, buffer.stored());
        long share = EnderTechRules.chargeShare(sendable, receivers.size());
        long sent = 0;
        for (Receiver r : receivers) {
            long left = share;
            for (EnergyStore item : r.items()) {
                if (left <= 0) {
                    break;
                }
                long n = item.receive(left, false);
                left -= n;
                sent += n;
            }
        }
        if (!tier.creative() && sent > 0) {
            buffer.extract(sent, false);
        }
        return sent;
    }

    /** A living entity in range of a Health Pad. */
    public interface Patient {
        boolean hurt();

        /** Heals one half heart. */
        void healHalfHeart();
    }

    /**
     * One Health Pad tick. Heals each hurt patient by half a heart when the interval has passed and the random
     * 1-in-(interval/4) gate opens, paying {@link EnderTechRules#HEAL_COST_PER_HALF_HEART} RF per half heart split
     * between patients (as the original). Returns the new "ticks since last heal" counter.
     */
    public static int heal(EnderTechRules.Tier tier, EnergyStore buffer, List<Patient> patients, int ticksSinceHeal, IntSupplier rng) {
        if (patients.isEmpty()) {
            return 0;
        }
        long costEach = EnderTechRules.HEAL_COST_PER_HALF_HEART / patients.size();
        boolean due = ticksSinceHeal > tier.ticksPerHeal && rng.getAsInt() % Math.max(1, tier.ticksPerHeal / 4) == 0;
        if (!due) {
            return Math.min(100, ticksSinceHeal + 1);
        }
        boolean healed = false;
        for (Patient p : patients) {
            if (!p.hurt()) {
                continue;
            }
            if (!tier.creative()) {
                if (buffer.extract(costEach, true) < costEach) {
                    continue;
                }
                buffer.extract(costEach, false);
            }
            p.healHalfHeart();
            healed = true;
        }
        return healed ? 0 : Math.min(100, ticksSinceHeal + 1);
    }
}
