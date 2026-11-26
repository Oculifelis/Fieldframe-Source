package dev.fieldframe.neoforge.client.manual;

import dev.fieldframe.api.id.FfId;
import dev.fieldframe.api.manual.ManualModel;
import dev.fieldframe.api.manual.ManualRegistry;
import dev.fieldframe.neoforge.api.client.ManualClientApi;
import dev.fieldframe.neoforge.api.client.ManualPageRenderer;
import dev.fieldframe.neoforge.client.Schematic;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Matcher;

/**
 * The Engineering Field Manual: a native, keyboard-usable service binder. Left: search, categories and entries
 * (or bookmarks). Right: the entry's pages rendered by registered page renderers, scrollable.
 * Keys: / search, Backspace back, Up/Down/PgUp/PgDn scroll, B bookmark, N note, Esc close.
 */
public final class ManualScreen extends Screen {
    private static final int W = 384;
    private static final int H = 236;
    private static final int SIDE = 118;

    // Session state survives closing and reopening the manual.
    private static final Deque<FfId> HISTORY = new ArrayDeque<>();
    private static FfId current;
    private static FfId openCategory = FfId.ff("basics");
    private static int scroll;
    private static boolean showBookmarks;
    private static String lastQuery = "";

    /** Count of page render failures (diagnostics and the client smoke test). */
    public static int RENDER_ERRORS;

    private int x0, y0;
    private EditBox search;
    private EditBox note;
    private boolean noteOpen;
    private int contentHeight;
    private int sideScroll;
    private long ticks;
    private final List<int[]> linkRects = new ArrayList<>();
    private final List<FfId> linkTargets = new ArrayList<>();
    private final List<Object[]> pageAreas = new ArrayList<>();
    private Component pendingTooltip;
    private int mx, my;

    public static ManualScreen open(FfId entry) {
        if (entry != null && !entry.equals(current)) {
            if (current != null) {
                HISTORY.push(current);
            }
            current = entry;
            scroll = 0;
        }
        if (current == null) {
            current = FfId.ff("basics/welcome");
        }
        ManualModel.Entry e = ManualRegistry.current().entries().get(current);
        if (e != null) {
            openCategory = e.category();
        }
        return new ManualScreen();
    }

    static void onBookReloaded() {
        // Entries are addressed by id, so history and bookmarks survive reloads; nothing to remap.
    }

    private ManualScreen() {
        super(Component.translatable("fieldframe.manual.title"));
    }

    @Override
    protected void init() {
        x0 = (width - W) / 2;
        y0 = (height - H) / 2;
        search = new EditBox(font, x0 + 6, y0 + 18, SIDE - 30, 12, Component.translatable("fieldframe.manual.search"));
        search.setHint(Component.translatable("fieldframe.manual.search_hint"));
        search.setValue(lastQuery);
        search.setResponder(s -> {
            lastQuery = s;
            sideScroll = 0;
        });
        addRenderableWidget(search);
        addRenderableWidget(Button.builder(Component.literal("★"), b -> showBookmarks = !showBookmarks).bounds(x0 + SIDE - 22, y0 + 18, 16, 12)
                .tooltip(Tooltip.create(Component.translatable("fieldframe.manual.bookmarks"))).build());
        addRenderableWidget(Button.builder(Component.literal("◀"), b -> back()).bounds(x0 + SIDE + 6, y0 + 4, 14, 12)
                .tooltip(Tooltip.create(Component.translatable("fieldframe.manual.back"))).build());
        addRenderableWidget(Button.builder(Component.literal("☆"), b -> {
            if (current != null) {
                ManualNotebook.toggleBookmark(current);
            }
        }).bounds(x0 + W - 38, y0 + 4, 14, 12).tooltip(Tooltip.create(Component.translatable("fieldframe.manual.bookmark"))).build());
        addRenderableWidget(Button.builder(Component.literal("✎"), b -> toggleNote()).bounds(x0 + W - 20, y0 + 4, 14, 12)
                .tooltip(Tooltip.create(Component.translatable("fieldframe.manual.note"))).build());
        note = new EditBox(font, x0 + SIDE + 6, y0 + H - 18, W - SIDE - 12, 12, Component.translatable("fieldframe.manual.note"));
        note.setMaxLength(ManualNotebook.MAX_NOTE);
        note.visible = false;
        addRenderableWidget(note);
    }

    private void toggleNote() {
        noteOpen = !noteOpen;
        note.visible = noteOpen;
        if (noteOpen && current != null) {
            note.setValue(ManualNotebook.note(current));
            setFocused(note);
        } else if (current != null) {
            ManualNotebook.setNote(current, note.getValue());
        }
    }

    private void navigate(FfId id) {
        if (id == null || id.equals(current)) {
            return;
        }
        if (noteOpen) {
            toggleNote();
        }
        if (current != null) {
            HISTORY.push(current);
            while (HISTORY.size() > 64) {
                HISTORY.removeLast();
            }
        }
        current = id;
        scroll = 0;
        ManualModel.Entry e = ManualRegistry.current().entries().get(id);
        if (e != null) {
            openCategory = e.category();
        }
    }

    private void back() {
        if (!HISTORY.isEmpty()) {
            current = HISTORY.pop();
            scroll = 0;
        }
    }

    @Override
    public void tick() {
        ticks++;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ------------------------------------------------------------------ sidebar model

    private List<Object> sideItems() {
        ManualRegistry.Book book = ManualRegistry.current();
        List<Object> items = new ArrayList<>();
        String q = search.getValue();
        if (!q.isBlank()) {
            items.addAll(book.search(q));
            return items;
        }
        if (showBookmarks) {
            for (FfId id : ManualNotebook.bookmarks()) {
                ManualModel.Entry e = book.entries().get(id);
                if (e != null) {
                    items.add(e);
                }
            }
            return items;
        }
        for (ManualModel.Category c : book.sortedCategories()) {
            items.add(c);
            if (c.id().equals(openCategory)) {
                items.addAll(book.entriesIn(c.id()));
            }
        }
        return items;
    }

    static String title(ManualModel.Entry e) {
        return BuiltinPages.localize(e.title());
    }

    // ------------------------------------------------------------------ rendering

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
        mx = mouseX;
        my = mouseY;
        super.extractRenderState(g, mouseX, mouseY, partial);
        if (pendingTooltip != null) {
            g.setTooltipForNextFrame(font, pendingTooltip, mouseX, mouseY);
            pendingTooltip = null;
        }
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
        super.extractBackground(g, mouseX, mouseY, partial);
        Schematic.panel(g, x0, y0, W, H);
        g.fill(x0 + SIDE, y0 + 1, x0 + SIDE + 1, y0 + H - 1, Schematic.STEEL);
        g.text(font, font.plainSubstrByWidth(I18n.get("fieldframe.manual.short_title"), SIDE - 10), x0 + 6, y0 + 6, Schematic.COPPER, false);
        renderSide(g);
        renderEntry(g);
    }

    private void renderSide(GuiGraphicsExtractor g) {
        List<Object> items = sideItems();
        int y = y0 + 36 - sideScroll;
        g.enableScissor(x0 + 2, y0 + 34, x0 + SIDE - 1, y0 + H - 2);
        for (Object o : items) {
            if (y > y0 + 30 && y < y0 + H) {
                if (o instanceof ManualModel.Category c) {
                    boolean open = c.id().equals(openCategory);
                    g.text(font, (open ? "▾ " : "▸ ") + font.plainSubstrByWidth(BuiltinPages.localize(c.title()), SIDE - 18), x0 + 6, y, Schematic.COPPER, false);
                } else if (o instanceof ManualModel.Entry e) {
                    boolean sel = e.id().equals(current);
                    boolean hover = mx >= x0 + 2 && mx < x0 + SIDE && my >= y - 1 && my < y + 9;
                    if (sel || hover) {
                        g.fill(x0 + 4, y - 1, x0 + SIDE - 3, y + 9, sel ? 0xFF35505F : 0xFF2A3D49);
                    }
                    ItemStack icon = BuiltinPages.icon(e.icon());
                    String t = font.plainSubstrByWidth(title(e), SIDE - 22);
                    g.text(font, t, x0 + 14, y, sel ? Schematic.INK : Schematic.INK_DIM, false);
                    if (!icon.isEmpty() && hover) {
                        pendingTooltip = Component.literal(title(e));
                    }
                }
            }
            y += 10;
        }
        g.disableScissor();
    }

    private void renderEntry(GuiGraphicsExtractor g) {
        linkRects.clear();
        linkTargets.clear();
        pageAreas.clear();
        int x = x0 + SIDE + 8;
        int w = W - SIDE - 16;
        ManualRegistry.Book book = ManualRegistry.current();
        ManualModel.Entry e = current == null ? null : book.entries().get(current);
        if (e == null) {
            g.text(font, I18n.get("fieldframe.manual.not_found", String.valueOf(current)), x + 20, y0 + 6, 0xFFE0524B, false);
            return;
        }
        g.text(font, font.plainSubstrByWidth(title(e), w - 60), x + 16, y0 + 6, Schematic.INK, false);
        if (ManualNotebook.isBookmarked(e.id())) {
            g.text(font, "★", x0 + W - 50, y0 + 6, 0xFFE3B341, false);
        }
        int top = y0 + 20;
        int bottom = y0 + H - (noteOpen ? 22 : 4);
        g.enableScissor(x - 2, top, x + w + 2, bottom);
        int y = top + 2 - scroll;
        Ctx ctx = new Ctx(g, top, bottom);
        if (!e.requiresMod().isEmpty() && !net.neoforged.fml.ModList.get().isLoaded(e.requiresMod())) {
            y += ctx.text(I18n.get("fieldframe.manual.requires_mod", e.requiresMod()), x, y, w, 0xFFE3B341) + 4;
        } else {
            for (ManualModel.Page p : e.pages()) {
                ManualPageRenderer r = ManualClientApi.renderer(p.type());
                int h;
                if (r == null) {
                    h = ctx.text(I18n.get("fieldframe.manual.unsupported_page", p.type().toString()), x, y, w, 0xFFE3B341);
                } else {
                    try {
                        h = r.render(ctx, p, x, y, w);
                    } catch (RuntimeException ex) {
                        RENDER_ERRORS++;
                        dev.fieldframe.neoforge.Fieldframe.LOG.error("Manual page {} of {} failed to render", p.type(), e.id(), ex);
                        h = ctx.text(I18n.get("fieldframe.manual.page_error", p.type().toString(), String.valueOf(ex.getMessage())), x, y, w, 0xFFE0524B);
                    }
                    pageAreas.add(new Object[]{r, p, y, h});
                }
                y += h + 8;
                g.fill(x, y - 5, x + w, y - 4, Schematic.GRID);
            }
        }
        if (!e.related().isEmpty()) {
            StringBuilder sb = new StringBuilder("**").append(I18n.get("fieldframe.manual.related")).append("** ");
            for (FfId r : e.related()) {
                ManualModel.Entry re = book.entries().get(r);
                sb.append("[[").append(r).append('|').append(re == null ? r.toString() : title(re).replace("]", "")).append("]]  ");
            }
            y += ctx.text(sb.toString(), x, y, w, Schematic.INK) + 4;
        }
        String noteText = ManualNotebook.note(e.id());
        if (!noteText.isEmpty() && !noteOpen) {
            y += ctx.text("✎ " + noteText, x, y, w, 0xFFB6D7A8) + 4;
        }
        contentHeight = y + scroll - top;
        g.disableScissor();
        int viewH = bottom - top;
        if (contentHeight > viewH) {
            int barH = Math.max(10, viewH * viewH / contentHeight);
            int barY = top + (int) ((viewH - barH) * (scroll / (double) Math.max(1, contentHeight - viewH)));
            g.fill(x0 + W - 4, barY, x0 + W - 2, barY + barH, Schematic.STEEL);
        }
    }

    private final class Ctx implements ManualPageRenderer.Context {
        private final GuiGraphicsExtractor g;
        private final int top, bottom;

        Ctx(GuiGraphicsExtractor g, int top, int bottom) {
            this.g = g;
            this.top = top;
            this.bottom = bottom;
        }

        @Override
        public GuiGraphicsExtractor graphics() {
            return g;
        }

        @Override
        public Font font() {
            return font;
        }

        @Override
        public int mouseX() {
            return mx;
        }

        @Override
        public int mouseY() {
            return my >= top && my < bottom ? my : -1000;
        }

        @Override
        public long ticks() {
            return ticks;
        }

        @Override
        public int text(String markup, int x, int y, int width, int color) {
            return drawMarkup(g, markup, x, y, width, color, this);
        }

        @Override
        public void link(FfId entry, int x, int y, int w, int h) {
            if (y + h >= top && y <= bottom) {
                linkRects.add(new int[]{x, y, w, h});
                linkTargets.add(entry);
            }
        }

        @Override
        public void item(ItemStack stack, int x, int y) {
            g.item(stack, x, y);
            if (mouseX() >= x && mouseX() < x + 16 && mouseY() >= y && mouseY() < y + 16 && !stack.isEmpty()) {
                pendingTooltip = stack.getHoverName();
            }
        }

        @Override
        public void tooltip(Component text) {
            pendingTooltip = text;
        }
    }

    /** Word-wrapping markup renderer: **bold** and [[ns:id|label]] links (underlined, clickable). */
    int drawMarkup(GuiGraphicsExtractor g, String markup, int x, int y, int width, int color, Ctx ctx) {
        List<String[]> tokens = new ArrayList<>();
        Matcher m = ManualRegistry.LINK.matcher(markup);
        int last = 0;
        while (m.find()) {
            addWords(tokens, markup.substring(last, m.start()), null);
            String label = m.group(2) != null ? m.group(2) : linkLabel(m.group(1));
            addWords(tokens, label, m.group(1));
            last = m.end();
        }
        addWords(tokens, markup.substring(last), null);
        int cx = x;
        int cy = y;
        boolean bold = false;
        for (String[] t : tokens) {
            String word = t[0];
            if (word.equals("\n")) {
                cx = x;
                cy += 10;
                continue;
            }
            String shown = word;
            boolean toggles = false;
            if (shown.contains("**")) {
                toggles = true;
                shown = shown.replace("**", "");
            }
            Component c = Component.literal(shown);
            if (bold || (toggles && word.startsWith("**"))) {
                c = c.copy().withStyle(s -> s.withBold(true));
            }
            int ww = font.width(c);
            if (cx > x && cx + ww > x + width) {
                cx = x;
                cy += 10;
            }
            boolean isLink = t[1] != null;
            int col = isLink ? 0xFF7FC4F0 : color;
            if (isLink) {
                c = c.copy().withStyle(s -> s.withUnderlined(true));
                FfId target = FfId.tryParse(t[1]);
                if (target != null) {
                    ctx.link(target, cx, cy - 1, ww, 10);
                }
            }
            g.text(font, c, cx, cy, col, false);
            cx += ww + font.width(" ");
            if (toggles) {
                int count = (word.length() - word.replace("**", "").length()) / 2;
                if (count % 2 == 1) {
                    bold = !bold;
                }
            }
        }
        return cy + 10 - y;
    }

    private static String linkLabel(String id) {
        ManualModel.Entry e = ManualRegistry.current().entries().get(FfId.tryParse(id));
        return e == null ? id : title(e);
    }

    private static void addWords(List<String[]> out, String text, String link) {
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                out.add(new String[]{"\n", null});
            }
            for (String w : lines[i].split(" ")) {
                if (!w.isEmpty()) {
                    out.add(new String[]{w, link});
                }
            }
        }
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
        double mouseX = event.x();
        double mouseY = event.y();
        int button = event.button();
        if (super.mouseClicked(event, doubleClick)) {
            return true;
        }
        for (int i = 0; i < linkRects.size(); i++) {
            int[] r = linkRects.get(i);
            if (mouseX >= r[0] && mouseX < r[0] + r[2] && mouseY >= r[1] && mouseY < r[1] + r[3]) {
                navigate(linkTargets.get(i));
                return true;
            }
        }
        for (Object[] a : pageAreas) {
            int py = (int) a[2];
            int ph = (int) a[3];
            if (mouseY >= py && mouseY < py + ph && ((ManualPageRenderer) a[0]).mouseClicked((ManualModel.Page) a[1], mouseX, mouseY, button)) {
                return true;
            }
        }
        if (mouseX >= x0 && mouseX < x0 + SIDE && mouseY >= y0 + 34) {
            int idx = (int) ((mouseY - (y0 + 35) + sideScroll) / 10);
            List<Object> items = sideItems();
            if (idx >= 0 && idx < items.size()) {
                Object o = items.get(idx);
                if (o instanceof ManualModel.Category c) {
                    openCategory = c.id().equals(openCategory) ? null : c.id();
                } else if (o instanceof ManualModel.Entry e) {
                    navigate(e.id());
                }
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double delta) {
        if (mouseX < x0 + SIDE) {
            sideScroll = Math.max(0, sideScroll - (int) (delta * 10));
        } else {
            scrollBy((int) (-delta * 12));
        }
        return true;
    }

    private void scrollBy(int d) {
        int viewH = H - 24;
        scroll = Math.max(0, Math.min(Math.max(0, contentHeight - viewH), scroll + d));
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        int key = event.key();
        if (getFocused() instanceof EditBox box && box.isFocused()) {
            if (key == GLFW.GLFW_KEY_ESCAPE || key == GLFW.GLFW_KEY_ENTER) {
                if (box == note) {
                    toggleNote();
                }
                box.setFocused(false);
                setFocused(null);
                return true;
            }
            return super.keyPressed(event);
        }
        switch (key) {
            case GLFW.GLFW_KEY_SLASH -> {
                setFocused(search);
                search.setFocused(true);
                return true;
            }
            case GLFW.GLFW_KEY_BACKSPACE -> {
                back();
                return true;
            }
            case GLFW.GLFW_KEY_DOWN -> {
                scrollBy(12);
                return true;
            }
            case GLFW.GLFW_KEY_UP -> {
                scrollBy(-12);
                return true;
            }
            case GLFW.GLFW_KEY_PAGE_DOWN -> {
                scrollBy(H - 40);
                return true;
            }
            case GLFW.GLFW_KEY_PAGE_UP -> {
                scrollBy(-(H - 40));
                return true;
            }
            case GLFW.GLFW_KEY_B -> {
                if (current != null) {
                    ManualNotebook.toggleBookmark(current);
                }
                return true;
            }
            case GLFW.GLFW_KEY_N -> {
                toggleNote();
                return true;
            }
            default -> {
                return super.keyPressed(event);
            }
        }
    }

    @Override
    public void removed() {
        if (noteOpen && current != null) {
            ManualNotebook.setNote(current, note.getValue());
        }
    }
}
