package dev.fieldframe.forge.client;

import dev.fieldframe.api.FieldframeRegistry;
import dev.fieldframe.api.face.LocalFace;
import dev.fieldframe.api.face.Orientation;
import dev.fieldframe.api.id.FfId;
import dev.fieldframe.api.module.ModuleDefinition;
import dev.fieldframe.forge.adapter.Dirs;
import dev.fieldframe.forge.chassis.ChassisBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.client.ChunkRenderTypeSet;
import net.minecraftforge.client.model.data.ModelData;
import net.minecraftforge.client.model.IDynamicBakedModel;

import java.util.ArrayList;
import java.util.List;

/**
 * Static chassis geometry with module faceplates composed in: each occupied face's base quads are re-textured with
 * that module's faceplate sprite ({@code fieldframe:block/module/<path>}, or the definition's {@code face_texture}
 * presentation hint). Rebuilt only when the chunk section re-meshes, so idle machines cost nothing per frame.
 */
public final class ChassisBakedModel implements IDynamicBakedModel {
    private final BakedModel base;

    public ChassisBakedModel(BakedModel base) {
        this.base = base;
    }

    static ResourceLocation faceTexture(FfId moduleId) {
        ModuleDefinition def = FieldframeRegistry.module(moduleId);
        if (def != null) {
            String hint = def.presentation().getString("face_texture", "");
            if (!hint.isEmpty()) {
                return new ResourceLocation(hint);
            }
        }
        return new ResourceLocation(moduleId.namespace(), "block/module/" + moduleId.path());
    }

    @Override
    public List<BakedQuad> getQuads(BlockState state, Direction side, RandomSource rand, ModelData data, RenderType renderType) {
        if (renderType == RenderType.cutout()) {
            return glowQuads(state, side, rand, data);
        }
        List<BakedQuad> quads = base.getQuads(state, side, rand, ModelData.EMPTY, renderType);
        ChassisBlockEntity.FaceRender fr = data.get(ChassisBlockEntity.FACES);
        if (side == null || fr == null) {
            return quads;
        }
        LocalFace local = new Orientation(fr.front()).toLocal(Dirs.toWorld(side));
        FfId module = fr.modules()[local.ordinal()];
        if (module == null) {
            return quads;
        }
        TextureAtlas atlas = Minecraft.getInstance().getModelManager().getAtlas(TextureAtlas.LOCATION_BLOCKS);
        TextureAtlasSprite sprite = atlas.getSprite(faceTexture(module));
        if (sprite == null || sprite.contents().name().getPath().equals("missingno")) {
            sprite = atlas.getSprite(new ResourceLocation("fieldframe", "block/module/generic"));
        }
        List<BakedQuad> out = new ArrayList<>(quads.size());
        for (BakedQuad q : quads) {
            out.add(retexture(q, sprite));
        }
        // Status lamp in the top-right corner: amber when the module is stuck, red on a fault.
        int lamp = fr.severity() == null ? -1 : dev.fieldframe.core.chassis.FaceGauge.statusLamp(fr.severity()[local.ordinal()]);
        if (lamp >= 0) {
            TextureAtlasSprite ls = atlas.getSprite(new ResourceLocation("fieldframe", dev.fieldframe.core.chassis.FaceGauge.TEXTURES.get(lamp)));
            for (BakedQuad q : quads) {
                BakedQuad lq = markQuad(q, ls, dev.fieldframe.core.chassis.FaceGauge.STATUS_LAMP, true);
                if (lq != null) {
                    out.add(lq);
                }
            }
        }
        // Gauge: lit lamps and a bar filled to the level the module shows (Indicator Panel).
        int gauge = fr.gauges() == null ? -1 : fr.gauges()[local.ordinal()];
        for (float[] lit : dev.fieldframe.core.chassis.FaceGauge.lit(gauge)) {
            TextureAtlasSprite gs = atlas.getSprite(new ResourceLocation("fieldframe", dev.fieldframe.core.chassis.FaceGauge.TEXTURES.get((int) lit[0])));
            for (BakedQuad q : quads) {
                BakedQuad gq = markQuad(q, gs, new float[]{lit[1], lit[2], lit[3], lit[4]}, true);
                if (gq != null) {
                    out.add(gq);
                }
            }
        }
        // Coloured ring (second pixel ring from the edge): which storage and tanks this module uses, one section each.
        byte[] marks = fr.marks() == null ? null : fr.marks()[local.ordinal()];
        if (marks != null) {
            for (int i = 0; i < Math.min(marks.length, dev.fieldframe.core.chassis.FaceMarks.MAX); i++) {
                TextureAtlasSprite ms = atlas.getSprite(new ResourceLocation("fieldframe", dev.fieldframe.core.chassis.FaceMarks.texture(marks[i])));
                for (float[] rect : dev.fieldframe.core.chassis.FaceMarks.ring(i, Math.min(marks.length, dev.fieldframe.core.chassis.FaceMarks.MAX))) {
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

    /** Cutout pass: the lit overlay of a working module (MachineLook.glowTexture); faceplates are in the solid pass. */
    private List<BakedQuad> glowQuads(BlockState state, Direction side, RandomSource rand, ModelData data) {
        ChassisBlockEntity.FaceRender fr = data.get(ChassisBlockEntity.FACES);
        if (side == null || fr == null || fr.working() == 0) {
            return List.of();
        }
        LocalFace local = new Orientation(fr.front()).toLocal(Dirs.toWorld(side));
        FfId module = fr.modules()[local.ordinal()];
        if (module == null || (fr.working() & (1 << local.ordinal())) == 0) {
            return List.of();
        }
        ResourceLocation glow = ResourceLocation.tryParse(dev.fieldframe.core.chassis.MachineLook.glowTexture(FieldframeRegistry.module(module)));
        if (glow == null) {
            return List.of();
        }
        TextureAtlasSprite sprite = Minecraft.getInstance().getModelManager().getAtlas(TextureAtlas.LOCATION_BLOCKS).getSprite(glow);
        if (sprite == null || sprite.contents().name().getPath().equals("missingno")) {
            return List.of();
        }
        List<BakedQuad> out = new ArrayList<>();
        for (BakedQuad q : base.getQuads(state, side, rand, ModelData.EMPTY, RenderType.solid())) {
            out.add(glowQuad(q, sprite));
        }
        return out;
    }

    /** Below the storage rings (FaceMarks.LIFT); the overlay never covers the ring pixels anyway. */
    static final float GLOW_LIFT = 0.001f;

    /** The faceplate quad re-textured with a lit overlay, just in front of the face, at full brightness. */
    static BakedQuad glowQuad(BakedQuad q, TextureAtlasSprite to) {
        int[] v = retexture(q, to).getVertices().clone();
        int stride = v.length / 4;
        Direction d = q.getDirection();
        for (int i = 0; i < 4; i++) {
            int o = i * stride;
            v[o] = Float.floatToRawIntBits(Float.intBitsToFloat(v[o]) + d.getStepX() * GLOW_LIFT);
            v[o + 1] = Float.floatToRawIntBits(Float.intBitsToFloat(v[o + 1]) + d.getStepY() * GLOW_LIFT);
            v[o + 2] = Float.floatToRawIntBits(Float.intBitsToFloat(v[o + 2]) + d.getStepZ() * GLOW_LIFT);
        }
        return net.minecraftforge.client.model.QuadTransformers.settingMaxEmissivity().process(new BakedQuad(v, -1, d, to, false, false));
    }

    /**
     * A small quad over texels {x0,y0,x1,y1} of the face of {@code q}, just in front of it, showing all of {@code to};
     * {@code lit} draws it full-bright and unshaded (gauge lamps).
     */
    static BakedQuad markQuad(BakedQuad q, TextureAtlasSprite to, float[] rect, boolean lit) {
        int[] v = q.getVertices().clone();
        TextureAtlasSprite from = q.getSprite();
        int stride = v.length / 4;
        float[][] pos = new float[4][3];
        float[][] uv = new float[4][2];
        for (int i = 0; i < 4; i++) {
            int o = i * stride;
            for (int k = 0; k < 3; k++) {
                pos[i][k] = Float.intBitsToFloat(v[o + k]);
            }
            uv[i][0] = (Float.intBitsToFloat(v[o + 4]) - from.getU0()) / Math.max(1e-6f, from.getU1() - from.getU0());
            uv[i][1] = (Float.intBitsToFloat(v[o + 5]) - from.getV0()) / Math.max(1e-6f, from.getV1() - from.getV0());
        }
        Direction d = q.getDirection();
        float[][] np = dev.fieldframe.core.chassis.FaceMarks.place(pos, uv, rect, d.getStepX(), d.getStepY(), d.getStepZ());
        if (np == null) {
            return null;
        }
        for (int i = 0; i < 4; i++) {
            int o = i * stride;
            for (int k = 0; k < 3; k++) {
                v[o + k] = Float.floatToRawIntBits(np[i][k]);
            }
            boolean lowU = dev.fieldframe.core.chassis.FaceMarks.lowU(uv, i);
            boolean lowV = dev.fieldframe.core.chassis.FaceMarks.lowV(uv, i);
            v[o + 4] = Float.floatToRawIntBits(lowU ? to.getU0() : to.getU1());
            v[o + 5] = Float.floatToRawIntBits(lowV ? to.getV0() : to.getV1());
        }
        if (lit) {
            return net.minecraftforge.client.model.QuadTransformers.settingMaxEmissivity().process(new BakedQuad(v, -1, d, to, false, false));
        }
        return new BakedQuad(v, -1, d, to, q.isShade());
    }

    /** Copies a quad, remapping its UVs from its sprite to {@code to}. Vertex format: BLOCK (8 ints/vertex). */
    static BakedQuad retexture(BakedQuad q, TextureAtlasSprite to) {
        int[] v = q.getVertices().clone();
        TextureAtlasSprite from = q.getSprite();
        int stride = v.length / 4;
        for (int i = 0; i < 4; i++) {
            int o = i * stride;
            float u = Float.intBitsToFloat(v[o + 4]);
            float w = Float.intBitsToFloat(v[o + 5]);
            v[o + 4] = Float.floatToRawIntBits(to.getU(from.getUOffset(u)));
            v[o + 5] = Float.floatToRawIntBits(to.getV(from.getVOffset(w)));
        }
        return new BakedQuad(v, q.getTintIndex(), q.getDirection(), to, q.isShade());
    }

    @Override
    public boolean useAmbientOcclusion() {
        return base.useAmbientOcclusion();
    }

    @Override
    public boolean isGui3d() {
        return base.isGui3d();
    }

    @Override
    public boolean usesBlockLight() {
        return base.usesBlockLight();
    }

    @Override
    public boolean isCustomRenderer() {
        return false;
    }

    @Override
    public TextureAtlasSprite getParticleIcon() {
        return base.getParticleIcon();
    }

    @Override
    public ItemOverrides getOverrides() {
        return base.getOverrides();
    }

    @Override
    @SuppressWarnings("deprecation")
    public ItemTransforms getTransforms() {
        return base.getTransforms();
    }

    @Override
    public ChunkRenderTypeSet getRenderTypes(BlockState state, RandomSource rand, ModelData data) {
        ChunkRenderTypeSet types = base.getRenderTypes(state, rand, data);
        ChassisBlockEntity.FaceRender fr = data.get(ChassisBlockEntity.FACES);
        return fr != null && fr.working() != 0 ? ChunkRenderTypeSet.union(types, ChunkRenderTypeSet.of(RenderType.cutout())) : types;
    }
}
