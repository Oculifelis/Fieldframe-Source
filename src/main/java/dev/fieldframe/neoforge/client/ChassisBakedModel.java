package dev.fieldframe.neoforge.client;

import dev.fieldframe.api.FieldframeRegistry;
import dev.fieldframe.api.face.LocalFace;
import dev.fieldframe.api.face.Orientation;
import dev.fieldframe.api.id.FfId;
import dev.fieldframe.api.module.ModuleDefinition;
import dev.fieldframe.neoforge.adapter.Dirs;
import dev.fieldframe.neoforge.chassis.ChassisBlockEntity;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.DynamicBlockStateModel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 26.1 composed chassis model: wraps the baked block-state model and re-textures the quads of each occupied face
 * with that module's faceplate sprite. Sprites are resolved once at bake time; geometry is static per chunk mesh.
 */
public final class ChassisBakedModel implements DynamicBlockStateModel {
    private final BlockStateModel base;
    private final Map<FfId, TextureAtlasSprite> sprites;
    /** Lit overlays drawn full-bright while a module works (MachineLook.glowTexture); absent textures are skipped. */
    private final Map<FfId, TextureAtlasSprite> glows = new HashMap<>();
    private final TextureAtlasSprite generic;
    /** Storage and tank mark sprites, by FaceMarks code 0..5. */
    private final TextureAtlasSprite[] marks = new TextureAtlasSprite[6];
    /** Gauge lamp and bar sprites, by FaceGauge sprite index. */
    private final TextureAtlasSprite[] gaugeSprites = new TextureAtlasSprite[dev.fieldframe.core.chassis.FaceGauge.TEXTURES.size()];

    public ChassisBakedModel(BlockStateModel base, Function<Identifier, TextureAtlasSprite> textures) {
        this.base = base;
        this.sprites = new HashMap<>();
        for (ModuleDefinition def : FieldframeRegistry.modules()) {
            TextureAtlasSprite s = textures.apply(faceTexture(def.id()));
            if (s != null) {
                sprites.put(def.id(), s);
            }
            String glow = dev.fieldframe.core.chassis.MachineLook.glowTexture(def);
            Identifier gl = glow.isEmpty() ? null : Identifier.tryParse(glow);
            TextureAtlasSprite g = gl == null ? null : textures.apply(gl);
            if (g != null && !g.contents().name().equals(net.minecraft.client.renderer.texture.MissingTextureAtlasSprite.getLocation())) {
                glows.put(def.id(), g);
            }
        }
        this.generic = textures.apply(Identifier.fromNamespaceAndPath("fieldframe", "block/module/generic"));
        for (int code = 0; code < marks.length; code++) {
            marks[code] = textures.apply(Identifier.fromNamespaceAndPath("fieldframe", dev.fieldframe.core.chassis.FaceMarks.texture(code)));
        }
        for (int i = 0; i < gaugeSprites.length; i++) {
            gaugeSprites[i] = textures.apply(Identifier.fromNamespaceAndPath("fieldframe", dev.fieldframe.core.chassis.FaceGauge.TEXTURES.get(i)));
        }
    }

    static Identifier faceTexture(FfId moduleId) {
        ModuleDefinition def = FieldframeRegistry.module(moduleId);
        if (def != null) {
            String hint = def.presentation().getString("face_texture", "");
            if (!hint.isEmpty()) {
                return Identifier.parse(hint);
            }
        }
        return Identifier.fromNamespaceAndPath(moduleId.namespace(), "block/module/" + moduleId.path());
    }

    @Override
    public void collectParts(BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random, List<BlockStateModelPart> parts) {
        List<BlockStateModelPart> baseParts = new ArrayList<>();
        base.collectParts(level, pos, state, random, baseParts);
        ChassisBlockEntity.FaceRender fr = level.getModelData(pos).get(ChassisBlockEntity.FACES);
        if (fr == null) {
            parts.addAll(baseParts);
            return;
        }
        for (BlockStateModelPart p : baseParts) {
            parts.add(new FacePart(p, fr));
        }
    }

    @Override
    public Material.Baked particleMaterial() {
        return base.particleMaterial();
    }

    @Override
    public int materialFlags() {
        return base.materialFlags();
    }

    private final class FacePart implements BlockStateModelPart {
        private final BlockStateModelPart inner;
        private final ChassisBlockEntity.FaceRender fr;

        FacePart(BlockStateModelPart inner, ChassisBlockEntity.FaceRender fr) {
            this.inner = inner;
            this.fr = fr;
        }

        @Override
        public List<BakedQuad> getQuads(Direction side) {
            List<BakedQuad> quads = inner.getQuads(side);
            if (side == null) {
                return quads;
            }
            LocalFace local = new Orientation(fr.front()).toLocal(Dirs.toWorld(side));
            FfId module = fr.modules()[local.ordinal()];
            if (module == null) {
                return quads;
            }
            TextureAtlasSprite to = sprites.getOrDefault(module, generic);
            if (to == null) {
                return quads;
            }
            List<BakedQuad> out = new ArrayList<>(quads.size());
            for (BakedQuad q : quads) {
                out.add(retexture(q, to));
            }
            // Coloured ring (second pixel ring from the edge): which storage and tanks this module uses, one section each.
            TextureAtlasSprite glow = (fr.working() & (1 << local.ordinal())) != 0 ? glows.get(module) : null;
            if (glow != null) {
                for (BakedQuad q : quads) {
                    out.add(glowQuad(q, glow));
                }
            }
            // Status lamp in the top-right corner: amber when the module is stuck, red on a fault.
            int lamp = fr.severity() == null ? -1 : dev.fieldframe.core.chassis.FaceGauge.statusLamp(fr.severity()[local.ordinal()]);
            if (lamp >= 0 && gaugeSprites[lamp] != null) {
                for (BakedQuad q : quads) {
                    BakedQuad lq = markQuad(q, gaugeSprites[lamp], dev.fieldframe.core.chassis.FaceGauge.STATUS_LAMP, true);
                    if (lq != null) {
                        out.add(lq);
                    }
                }
            }
            // Gauge: lit lamps and a bar filled to the level the module shows (Indicator Panel).
            int gauge = fr.gauges() == null ? -1 : fr.gauges()[local.ordinal()];
            for (float[] lit : dev.fieldframe.core.chassis.FaceGauge.lit(gauge)) {
                TextureAtlasSprite gs = gaugeSprites[(int) lit[0]];
                if (gs == null) {
                    continue;
                }
                for (BakedQuad q : quads) {
                    BakedQuad gq = markQuad(q, gs, new float[]{lit[1], lit[2], lit[3], lit[4]}, true);
                    if (gq != null) {
                        out.add(gq);
                    }
                }
            }
            byte[] m = fr.marks() == null ? null : fr.marks()[local.ordinal()];
            if (m != null) {
                for (int i = 0; i < Math.min(m.length, dev.fieldframe.core.chassis.FaceMarks.MAX); i++) {
                    TextureAtlasSprite ms = m[i] >= 0 && m[i] < marks.length ? marks[m[i]] : null;
                    if (ms == null) {
                        continue;
                    }
                    for (float[] rect : dev.fieldframe.core.chassis.FaceMarks.ring(i, Math.min(m.length, dev.fieldframe.core.chassis.FaceMarks.MAX))) {
                        for (BakedQuad q : quads) {
                            BakedQuad mq = markQuad(q, ms, rect, false);
                            if (mq != null) {
                                out.add(mq);
                            }
                        }
                    }
                }
            }
            return out;
        }

        @Override
        @SuppressWarnings("deprecation")
        public boolean useAmbientOcclusion() {
            return inner.useAmbientOcclusion();
        }

        @Override
        public Material.Baked particleMaterial() {
            return inner.particleMaterial();
        }

        @Override
        public int materialFlags() {
            return inner.materialFlags();
        }
    }

    /**
     * A small quad over texels {x0,y0,x1,y1} of the face of {@code q}, just in front of it, showing all of {@code to};
     * {@code lit} draws it full-bright and unshaded (gauge lamps).
     */
    static BakedQuad markQuad(BakedQuad q, TextureAtlasSprite to, float[] rect, boolean lit) {
        BakedQuad.MaterialInfo m = q.materialInfo();
        TextureAtlasSprite from = m.sprite();
        float[][] pos = new float[4][3];
        float[][] uv = new float[4][2];
        for (int i = 0; i < 4; i++) {
            org.joml.Vector3fc p = q.position(i);
            pos[i][0] = p.x();
            pos[i][1] = p.y();
            pos[i][2] = p.z();
            long packed = q.packedUV(i);
            uv[i][0] = (UVPair.unpackU(packed) - from.getU0()) / Math.max(1e-6f, from.getU1() - from.getU0());
            uv[i][1] = (UVPair.unpackV(packed) - from.getV0()) / Math.max(1e-6f, from.getV1() - from.getV0());
        }
        Direction d = q.direction();
        float[][] np = dev.fieldframe.core.chassis.FaceMarks.place(pos, uv, rect, d.getStepX(), d.getStepY(), d.getStepZ());
        if (np == null) {
            return null;
        }
        org.joml.Vector3f[] v = new org.joml.Vector3f[4];
        long[] t = new long[4];
        for (int i = 0; i < 4; i++) {
            v[i] = new org.joml.Vector3f(np[i][0], np[i][1], np[i][2]);
            boolean lowU = dev.fieldframe.core.chassis.FaceMarks.lowU(uv, i);
            boolean lowV = dev.fieldframe.core.chassis.FaceMarks.lowV(uv, i);
            t[i] = UVPair.pack(lowU ? to.getU0() : to.getU1(), lowV ? to.getV0() : to.getV1());
        }
        BakedQuad.MaterialInfo nm = lit
                ? new BakedQuad.MaterialInfo(to, m.layer(), m.itemRenderType(), -1, false, 15, false)
                : new BakedQuad.MaterialInfo(to, m.layer(), m.itemRenderType(), -1, m.shade(), m.lightEmission(), m.ambientOcclusion());
        return new BakedQuad(v[0], v[1], v[2], v[3], t[0], t[1], t[2], t[3], d, nm, q.bakedNormals(), q.bakedColors());
    }

    /** The faceplate quad re-textured with a lit overlay: cutout, full-bright, just in front of the face. */
    static BakedQuad glowQuad(BakedQuad q, TextureAtlasSprite to) {
        BakedQuad.MaterialInfo m = q.materialInfo();
        TextureAtlasSprite from = m.sprite();
        Direction d = q.direction();
        float lx = d.getStepX() * GLOW_LIFT;
        float ly = d.getStepY() * GLOW_LIFT;
        float lz = d.getStepZ() * GLOW_LIFT;
        BakedQuad.MaterialInfo nm = new BakedQuad.MaterialInfo(to, net.minecraft.client.renderer.chunk.ChunkSectionLayer.CUTOUT, m.itemRenderType(), -1, false, 15, false);
        return new BakedQuad(new org.joml.Vector3f(q.position0()).add(lx, ly, lz), new org.joml.Vector3f(q.position1()).add(lx, ly, lz),
                new org.joml.Vector3f(q.position2()).add(lx, ly, lz), new org.joml.Vector3f(q.position3()).add(lx, ly, lz),
                remap(q.packedUV0(), from, to), remap(q.packedUV1(), from, to), remap(q.packedUV2(), from, to), remap(q.packedUV3(), from, to),
                d, nm, q.bakedNormals(), q.bakedColors());
    }

    /** Below the storage rings (FaceMarks.LIFT); the overlay never covers the ring pixels anyway. */
    static final float GLOW_LIFT = 0.001f;

    private static long remap(long packed, TextureAtlasSprite from, TextureAtlasSprite to) {
        float u = UVPair.unpackU(packed);
        float v = UVPair.unpackV(packed);
        float ou = (u - from.getU0()) / Math.max(1e-6f, from.getU1() - from.getU0());
        float ov = (v - from.getV0()) / Math.max(1e-6f, from.getV1() - from.getV0());
        return UVPair.pack(to.getU(ou), to.getV(ov));
    }

    static BakedQuad retexture(BakedQuad q, TextureAtlasSprite to) {
        BakedQuad.MaterialInfo m = q.materialInfo();
        TextureAtlasSprite from = m.sprite();
        BakedQuad.MaterialInfo nm = new BakedQuad.MaterialInfo(to, m.layer(), m.itemRenderType(), m.tintIndex(), m.shade(), m.lightEmission(), m.ambientOcclusion());
        return new BakedQuad(q.position0(), q.position1(), q.position2(), q.position3(),
                remap(q.packedUV0(), from, to), remap(q.packedUV1(), from, to), remap(q.packedUV2(), from, to), remap(q.packedUV3(), from, to),
                q.direction(), nm, q.bakedNormals(), q.bakedColors());
    }
}
