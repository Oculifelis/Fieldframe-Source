package dev.fieldframe.core.gui;

/**
 * Open/close motion of one side tab, measured in wall-clock milliseconds so it looks the same at any frame rate.
 * Opens in 180 ms on cubic-bezier(.22, 1, .36, 1); content fades in over the last half. Closes in 120 ms.
 */
public final class TabMotion {
    public static final long OPEN_MS = 180, CLOSE_MS = 120;

    private boolean open;
    private double from;
    private long start = -1;

    /** Set the target state; a change mid-way carries on from where the tab is now. */
    public void set(boolean open, long now) {
        if (this.open == open) {
            return;
        }
        from = fraction(now);
        this.open = open;
        start = now;
    }

    /** Jump straight to a state (used when a screen reopens with a remembered tab). */
    public void snap(boolean open) {
        this.open = open;
        from = open ? 1 : 0;
        start = -1;
    }

    public boolean isOpen() {
        return open;
    }

    /** Eased open amount, 0 (closed) to 1 (open). */
    public double fraction(long now) {
        double target = open ? 1 : 0;
        if (start < 0) {
            return target;
        }
        long dur = open ? OPEN_MS : CLOSE_MS;
        double t = Math.min(1, Math.max(0, (now - start) / (double) dur));
        if (t >= 1) {
            start = -1;
            from = target;
            return target;
        }
        double e = open ? bezier(t) : 1 - Math.pow(1 - t, 3);
        return from + (target - from) * e;
    }

    /** Content opacity, 0..1: nothing in the first half of opening, then a fade. */
    public double contentAlpha(long now) {
        double f = fraction(now);
        return Math.max(0, Math.min(1, (f - 0.5) * 2));
    }

    public boolean moving(long now) {
        fraction(now);
        return start >= 0;
    }

    /** cubic-bezier(.22, 1, .36, 1), solved for x = t by Newton steps. */
    static double bezier(double x) {
        double x1 = 0.22, y1 = 1, x2 = 0.36, y2 = 1;
        double u = x;
        for (int i = 0; i < 8; i++) {
            double bx = 3 * (1 - u) * (1 - u) * u * x1 + 3 * (1 - u) * u * u * x2 + u * u * u - x;
            double dx = 3 * (1 - u) * (1 - u) * x1 + 6 * (1 - u) * u * (x2 - x1) + 3 * u * u * (1 - x2);
            if (Math.abs(dx) < 1e-6) {
                break;
            }
            u = Math.max(0, Math.min(1, u - bx / dx));
        }
        return 3 * (1 - u) * (1 - u) * u * y1 + 3 * (1 - u) * u * u * y2 + u * u * u;
    }

    /** Linear blend between closed and open size. */
    public static int lerp(int closed, int open, double f) {
        return (int) Math.round(closed + (open - closed) * f);
    }
}
