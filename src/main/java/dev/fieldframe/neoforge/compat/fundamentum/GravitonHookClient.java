package dev.fieldframe.neoforge.compat.fundamentum;

import com.fundamentum.hook.HookEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.util.List;

/**
 * Client half of the Graviton Hook ({@link FieldHooks}): while the field is up, the wearer flies where they steer
 * (forward follows the look, up and down included; sneak sinks) instead of being pulled to the hooks, and is drawn back
 * only past {@link FieldHooks#TETHER} blocks from the hooks' middle. Runs after Fundamentum's hook steering
 * (LOWEST priority) and replaces it; jumping still lets go, as with every Fundamentum hook.
 */
public final class GravitonHookClient {
    /** Blocks per tick: walking pace in the field, a little faster sprinting. */
    static final double SPEED = 0.28, SPRINT = 0.4;
    /** How much of last tick's motion carries over, so starts and stops ease. */
    static final double CARRY = 0.6;

    public static void init() {
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, GravitonHookClient::tick);
    }

    private static void tick(ClientTickEvent.Post e) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || player.isPassenger() || player.isSpectator() || mc.options.keyJump.isDown() || !FieldHooks.floating(player)) {
            return;
        }
        List<HookEntity> latched = player.level().getEntitiesOfClass(HookEntity.class, player.getBoundingBox().inflate(64),
                h -> h.getOwner() == player && h.isLatched());
        if (latched.isEmpty()) {
            return;
        }
        Vec3 centre = Vec3.ZERO;
        for (HookEntity hook : latched) {
            centre = centre.add(hook.position());
        }
        centre = centre.scale(1.0 / latched.size());

        Vec2 move = player.input.getMoveVector();
        Vec3 look = player.getLookAngle();
        Vec3 flat = new Vec3(look.x, 0, look.z);
        Vec3 left = flat.lengthSqr() < 1.0E-6 ? Vec3.ZERO : new Vec3(0, 1, 0).cross(flat).normalize();
        Vec3 wish = look.scale(move.y).add(left.scale(move.x));
        if (mc.options.keyShift.isDown()) {
            wish = wish.add(0, -1, 0);
        }
        if (wish.lengthSqr() > 1.0) {
            wish = wish.normalize();
        }
        Vec3 velocity = player.getDeltaMovement().scale(CARRY).add(wish.scale((player.isSprinting() ? SPRINT : SPEED) * (1.0 - CARRY)));

        Vec3 toCentre = centre.subtract(player.position().add(0, player.getBbHeight() * 0.5, 0));
        double distance = toCentre.length();
        if (distance > FieldHooks.TETHER) {
            Vec3 in = toCentre.scale(1.0 / distance);
            double outward = velocity.dot(in);
            if (outward < 0) {
                velocity = velocity.subtract(in.scale(outward));
            }
            velocity = velocity.add(in.scale(Math.min(distance - FieldHooks.TETHER, 0.5)));
        }
        player.setDeltaMovement(velocity);
        player.resetFallDistance();
    }

    private GravitonHookClient() {}
}
