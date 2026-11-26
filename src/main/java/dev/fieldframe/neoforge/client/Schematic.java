package dev.fieldframe.neoforge.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Shared drawing vocabulary for Fieldframe screens: a blueprint-paper panel, traced connections and status
 * symbols that pair colour with a letter and shape so status is readable without colour vision.
 */
public final class Schematic {
    private Schematic() {
    }

    public static final int PAPER = 0xFF1E2A33;
    public static final int PAPER_LIGHT = 0xFF26343F;
    public static final int GRID = 0xFF2C3C48;
    public static final int INK = 0xFFD8E4EA;
    public static final int INK_DIM = 0xFF8FA3AF;
    public static final int COPPER = 0xFFD08A4E;
    public static final int STEEL = 0xFF5B6770;
    public static final int SLOT = 0xFF121A20;

    public static void panel(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x - 1, y - 1, x + w + 1, y + h + 1, 0xFF0B1014);
        g.fill(x, y, x + w, y + h, PAPER);
        for (int gx = x + 8; gx < x + w; gx += 8) {
            g.fill(gx, y, gx + 1, y + h, GRID);
        }
        for (int gy = y + 8; gy < y + h; gy += 8) {
            g.fill(x, gy, x + w, gy + 1, GRID);
        }
        g.fill(x, y, x + w, y + 1, STEEL);
        g.fill(x, y + h - 1, x + w, y + h, STEEL);
        g.fill(x, y, x + 1, y + h, STEEL);
        g.fill(x + w - 1, y, x + w, y + h, STEEL);
    }

    public static void slot(GuiGraphics g, int x, int y) {
        g.fill(x - 1, y - 1, x + 17, y + 17, STEEL);
        g.fill(x, y, x + 16, y + 16, SLOT);
    }

    /** Severity: WORKING, IDLE, DISABLED, BLOCKED, FAULT. */
    public static int severityColor(String severity) {
        return switch (severity) {
            case "WORKING" -> 0xFF5BC26A;
            case "DISABLED" -> 0xFF7F8FA0;
            case "BLOCKED" -> 0xFFE3B341;
            case "FAULT" -> 0xFFE0524B;
            default -> 0xFF5AA7D6;
        };
    }

    /** Letter used with the colour: W(orking) I(dle) D(isabled) B(locked) F(ault). */
    public static String severityLetter(String severity) {
        return switch (severity) {
            case "WORKING" -> "W";
            case "DISABLED" -> "D";
            case "BLOCKED" -> "!";
            case "FAULT" -> "X";
            default -> "i";
        };
    }

    public static void lamp(GuiGraphics g, Font font, int x, int y, String severity) {
        int c = severityColor(severity);
        g.fill(x, y, x + 9, y + 9, 0xFF0B1014);
        g.fill(x + 1, y + 1, x + 8, y + 8, c);
        g.drawString(font, severityLetter(severity), x + 2, y + 1, 0xFF0B1014, false);
    }

    public static void hline(GuiGraphics g, int x1, int x2, int y, int color) {
        g.fill(Math.min(x1, x2), y, Math.max(x1, x2) + 1, y + 1, color);
    }

    public static void vline(GuiGraphics g, int x, int y1, int y2, int color) {
        g.fill(x, Math.min(y1, y2), x + 1, Math.max(y1, y2) + 1, color);
    }

    public static void bar(GuiGraphics g, int x, int y, int w, int h, double frac, int color) {
        g.fill(x, y, x + w, y + h, SLOT);
        int fw = (int) Math.round(Math.max(0, Math.min(1, frac)) * (w - 2));
        g.fill(x + 1, y + 1, x + 1 + fw, y + h - 1, color);
    }
}
