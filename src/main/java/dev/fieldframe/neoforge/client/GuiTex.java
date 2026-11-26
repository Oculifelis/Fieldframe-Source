package dev.fieldframe.neoforge.client;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Screen art painted in code (ARGB arrays from {@code WorkshopArt}) turned into textures once and cached by key.
 * The only version-specific part of the Workshop art: the texture upload and the blit call.
 */
final class GuiTex {
    private static final Map<String, Identifier> CACHE = new HashMap<>();

    private GuiTex() {
    }

    /** The texture for {@code key}, painted by {@code pixels} (w x h ARGB, row by row) the first time it is asked for. */
    static Identifier get(String key, int w, int h, Supplier<int[]> pixels) {
        Identifier id = CACHE.get(key);
        if (id != null) {
            return id;
        }
        int[] px = pixels.get();
        NativeImage img = new NativeImage(w, h, false);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                img.setPixel(x, y, px[y * w + x]);
            }
        }
        id = Identifier.fromNamespaceAndPath("fieldframe", "dynamic/" + key);
        Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(() -> "fieldframe " + key, img));
        CACHE.put(key, id);
        return id;
    }

    /** Draws a w x h piece of a texture starting at (u, v). */
    static void blit(GuiGraphicsExtractor g, Identifier tex, int x, int y, int u, int v, int w, int h, int texW, int texH) {
        g.blit(RenderPipelines.GUI_TEXTURED, tex, x, y, u, v, w, h, texW, texH);
    }

    /** A texture made of {@code frames} pictures of w x h stacked top to bottom. */
    static Identifier strip(String key, int w, int h, int frames, java.util.function.IntFunction<int[]> frame) {
        return get(key, w, h * frames, () -> {
            int[] out = new int[w * h * frames];
            for (int f = 0; f < frames; f++) {
                System.arraycopy(frame.apply(f), 0, out, f * w * h, w * h);
            }
            return out;
        });
    }
}
