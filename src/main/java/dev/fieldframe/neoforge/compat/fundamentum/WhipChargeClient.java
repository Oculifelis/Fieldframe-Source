package dev.fieldframe.neoforge.compat.fundamentum;

import com.fundamentum.whip.WhipShaders;
import com.fundamentum.whip.WhipStyle;
import com.fundamentum.whip.WhipsClient;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.fieldframe.neoforge.Fieldframe;
import dev.fieldframe.neoforge.feature.ItemEnergy;
import dev.fieldframe.neoforge.item.ChargedGearItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * How a charged electric whip looks over Fundamentum's plain copper cord (client only): a second, unlit ribbon in the
 * whip's glow strip ({@code textures/entity/whip/<whip>_glow.png}, {@link #FRAMES} frames of 8x32 stacked, picked by
 * game time, so current runs down the core and sparks flick on and off) a hair in front of the cord, a soft pulsing
 * halo a hair behind it (an additive halo at the cord's own depth flickers), jagged unlit arcs with halos of their own
 * that flicker off the cord, and a glowing tip. No particles: Oculifelis asked for the white sparks gone (2026-11-04).
 * Unlit means full colour at night too, which a full-bright light value alone is not. An empty whip (less than one
 * swing of RF) shows none of it.
 */
public final class WhipChargeClient implements WhipStyle {
    static final int FRAMES = 8;
    private static final Identifier WHITE = Identifier.fromNamespaceAndPath("fundamentum", "textures/misc/white.png");
    private static final int CORE = 0xFFF0FCFF, BOLT_GLOW = 0x9048C8FF;
    /** Depth offsets: the points are camera-relative, so scaling them pulls geometry toward or away from the eye. */
    private static final double FRONT = 0.996, BEHIND = 1.006;

    private final Identifier glow;
    /** Arcs drawn per strand each flicker, how far they reach (blocks), and whether some jump between cord points. */
    private final int arcs;
    private final double reach;
    private final boolean bridges;
    /** The cord's halo colour (ARGB, alpha its strength at the pulse's peak). */
    private final int halo;

    private WhipChargeClient(String whip, int arcs, double reach, boolean bridges, int halo) {
        this.glow = Identifier.fromNamespaceAndPath(Fieldframe.MOD_ID, "textures/entity/whip/" + whip + "_glow.png");
        this.arcs = arcs;
        this.reach = reach;
        this.bridges = bridges;
        this.halo = halo;
    }

    public static void register() {
        WhipsClient.style(FieldWhips.ARC_WHIP.getId(), new WhipChargeClient("arc_whip", 3, 0.35, false, 0xB040C8FF));
        WhipsClient.style(FieldWhips.TESLA_WHIP.getId(), new WhipChargeClient("tesla_whip", 6, 0.6, true, 0xD070B8FF));
    }

    /** The whip in the player's main hand has at least one swing of RF. */
    private static boolean charged(Player player) {
        ItemStack stack = player.getMainHandItem();
        return stack.getItem() instanceof ChargedGearItem gear && ItemEnergy.get(stack) >= gear.charge().perUse();
    }

    @Override
    public void draw(Cord cord, PoseStack pose, SubmitNodeCollector collector) {
        Vec3[] p = cord.points();
        if (p.length < 2 || !charged(cord.player())) {
            return;
        }
        ClientLevel level = Minecraft.getInstance().level;
        long time = level == null ? 0 : level.getGameTime();
        int frame = (int) ((time / 2) % FRAMES);
        Vec3[] front = scaled(p, FRONT), behind = scaled(p, BEHIND);
        float pulse = 0.75F + 0.25F * Mth.sin(time * 0.35F + cord.strand());
        int haloColor = ((int) ((halo >>> 24) * pulse) << 24) | (halo & 0xFFFFFF);
        double width = cord.whip().width, tipWidth = cord.whip().tipWidth;
        collector.submitCustomGeometry(pose, WhipShaders.halo(), (at, buffer) -> {
            WhipsClient.halo(at, buffer, behind, cord.span(), width * 4.5, tipWidth * 5.0, haloColor);
            if (cord.span() > 0.95) {
                WhipsClient.glowDot(at, buffer, behind[p.length - 1], 0.18 + 0.06 * pulse, haloColor);
            }
        });
        collector.submitCustomGeometry(pose, WhipShaders.unlit(glow, true), (at, buffer) -> ribbon(at, buffer, cord, front, frame));
        // Arcs re-roll every other tick, the same on every client for the same player and strand.
        List<Vec3[]> bolts = arcs(cord, front, RandomSource.create(time / 2 * 31L + cord.player().getId() * 7L + cord.strand()));
        collector.submitCustomGeometry(pose, WhipShaders.halo(), (at, buffer) -> {
            for (Vec3[] bolt : bolts) {
                Vec3[] back = scaled(bolt, BEHIND);
                WhipsClient.halo(at, buffer, back, 1.0, 0.16, 0.1, BOLT_GLOW);
                WhipsClient.glowDot(at, buffer, back[back.length - 1], 0.09, BOLT_GLOW);
            }
        });
        collector.submitCustomGeometry(pose, WhipShaders.unlit(WHITE, true), (at, buffer) -> {
            for (Vec3[] bolt : bolts) {
                for (int j = 0; j < bolt.length - 1; j++) {
                    strip(at, buffer, bolt[j], bolt[j + 1], 0.018, CORE);
                }
            }
        });
    }

    private static Vec3[] scaled(Vec3[] p, double by) {
        Vec3[] out = new Vec3[p.length];
        for (int i = 0; i < p.length; i++) {
            out[i] = p[i].scale(by);
        }
        return out;
    }

    /** Short jagged bolts off the cord, as polylines; on the Tesla Whip some leap from one point of the cord to another. */
    private List<Vec3[]> arcs(Cord cord, Vec3[] p, RandomSource random) {
        List<Vec3[]> out = new ArrayList<>();
        for (int k = 0; k < arcs; k++) {
            if (random.nextFloat() < 0.3F) {
                continue;                                     // a flicker: not every arc shows every time
            }
            int i = 1 + random.nextInt(p.length - 1);
            Vec3 from = p[i], to;
            if (bridges && k % 2 == 1 && p.length > 6) {
                to = p[Math.min(p.length - 1, i + 2 + random.nextInt(4))];
            } else {
                Vec3 off = new Vec3(random.nextGaussian(), random.nextGaussian(), random.nextGaussian());
                off = off.subtract(cord.direction(i).scale(off.dot(cord.direction(i))));
                to = from.add(off.lengthSqr() < 1.0E-6 ? new Vec3(0, reach, 0) : off.normalize().scale(reach * (0.5 + random.nextDouble())));
            }
            out.add(bolt(from, to, random));
        }
        return out;
    }

    /** A zigzag from {@code from} to {@code to} in five kinks. */
    private static Vec3[] bolt(Vec3 from, Vec3 to, RandomSource random) {
        int kinks = 5;
        Vec3[] pts = new Vec3[kinks + 1];
        double jitter = from.distanceTo(to) * 0.22;
        for (int j = 0; j <= kinks; j++) {
            Vec3 on = from.lerp(to, j / (double) kinks);
            pts[j] = j == 0 || j == kinks ? on
                    : on.add(random.nextGaussian() * jitter, random.nextGaussian() * jitter, random.nextGaussian() * jitter);
        }
        return pts;
    }

    private static void strip(PoseStack.Pose at, VertexConsumer buffer, Vec3 a, Vec3 b, double half, int color) {
        Vec3 side = b.subtract(a).cross(a.add(b).scale(-0.5));
        if (side.lengthSqr() < 1.0E-12) {
            return;
        }
        side = side.normalize().scale(half);
        vertex(at, buffer, a.add(side), 0, 0, color);
        vertex(at, buffer, a.subtract(side), 1, 0, color);
        vertex(at, buffer, b.subtract(side), 1, 1, color);
        vertex(at, buffer, b.add(side), 0, 1, color);
    }

    /** Fundamentum's camera-facing strip again (u across, v handle to tip) along {@code p}, in this frame of the glow strip. */
    private static void ribbon(PoseStack.Pose at, VertexConsumer buffer, Cord cord, Vec3[] p, int frame) {
        int n = p.length;
        Vec3[] left = new Vec3[n], right = new Vec3[n];
        for (int i = 0; i < n; i++) {
            Vec3 tangent = p[Math.min(n - 1, i + 1)].subtract(p[Math.max(0, i - 1)]);
            Vec3 side = tangent.cross(p[i].scale(-1));
            side = side.lengthSqr() < 1.0E-10 ? new Vec3(0, 1, 0) : side.normalize();
            double half = Mth.lerp(cord.along(i), cord.whip().width, cord.whip().tipWidth) * 0.5;
            left[i] = p[i].add(side.scale(half));
            right[i] = p[i].subtract(side.scale(half));
        }
        for (int i = 0; i < n - 1; i++) {
            float v0 = (float) ((frame + cord.along(i)) / FRAMES), v1 = (float) ((frame + cord.along(i + 1)) / FRAMES);
            vertex(at, buffer, left[i], 0, v0, 0xFFFFFFFF);
            vertex(at, buffer, right[i], 1, v0, 0xFFFFFFFF);
            vertex(at, buffer, right[i + 1], 1, v1, 0xFFFFFFFF);
            vertex(at, buffer, left[i + 1], 0, v1, 0xFFFFFFFF);
        }
    }

    private static void vertex(PoseStack.Pose at, VertexConsumer buffer, Vec3 p, float u, float v, int color) {
        buffer.addVertex(at, (float) p.x, (float) p.y, (float) p.z).setColor(color).setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightCoordsUtil.FULL_BRIGHT).setNormal(at, 0, 1, 0);
    }
}
