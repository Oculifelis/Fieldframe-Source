package dev.fieldframe.frameworks;

import dev.fieldframe.api.resource.EnergyStore;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Starcore's plasma model: it ignites, runs away without control, holds with feedback and lights again after an escape. */
class PlasmaTest {
    /** A plain RF buffer. */
    static final class Buffer implements EnergyStore {
        long stored;
        final long capacity;

        Buffer(long capacity) {
            this.capacity = capacity;
            this.stored = capacity;
        }

        @Override
        public long stored() {
            return stored;
        }

        @Override
        public long capacity() {
            return capacity;
        }

        @Override
        public long receive(long amount, boolean simulate) {
            long n = Math.min(amount, capacity - stored);
            if (!simulate) {
                stored += n;
            }
            return n;
        }

        @Override
        public long extract(long amount, boolean simulate) {
            long n = Math.min(amount, stored);
            if (!simulate) {
                stored -= n;
            }
            return n;
        }
    }

    /** Eight magnets at full and one injector at {@code rate}; magnets pay from the buffer while lit. */
    private static Plasma.Event step(Plasma p, Buffer b, int rate) {
        for (int i = 0; i < 8; i++) {
            if (p.needsField()) {
                long cost = p.lit() ? 15 * Plasma.rfPerLevel() : 0;
                if (b.extract(cost, true) == cost) {
                    b.extract(cost, false);
                    p.addField(15 * Plasma.fieldPerLevel());
                }
            }
        }
        if (p.wantsFuel()) {
            p.inject(rate / 15.0);
        }
        return p.tick(b);
    }

    private static Plasma core() {
        Plasma p = new Plasma();
        p.setCoils(4);
        return p;
    }

    @Test
    void ignitesOnceFuelledFieldedAndPowered() {
        Plasma p = core();
        Buffer b = new Buffer(6_016_000);
        Plasma.Event ev = Plasma.Event.NONE;
        int t = 0;
        while (ev != Plasma.Event.IGNITED && t++ < 100) {
            ev = step(p, b, 15);
        }
        assertEquals(Plasma.Event.IGNITED, ev, "ignites after gathering " + Plasma.ignitionFuel() + " mB");
        assertEquals(6_016_000 - Plasma.ignitionRf(), b.stored(), "ignition takes 5,000,000 RF");
        assertEquals(Plasma.ignitionMk(), p.temperature());
    }

    @Test
    void doesNotIgniteWithoutEnoughPower() {
        Plasma p = core();
        Buffer b = new Buffer(4_000_000);
        for (int t = 0; t < 200; t++) {
            assertEquals(Plasma.Event.NONE, step(p, b, 15));
        }
        assertFalse(p.lit());
        assertEquals(Plasma.ignitionFuel(), p.fuel(), "fuel waits, it is not wasted");
    }

    @Test
    void runsAwayWithoutControl() {
        Plasma p = core();
        Buffer b = new Buffer(6_016_000);
        Plasma.Event ev = Plasma.Event.NONE;
        int t = 0;
        while (ev != Plasma.Event.ESCAPED && t++ < 2_000) {
            ev = step(p, b, 15);
        }
        assertEquals(Plasma.Event.ESCAPED, ev, "full injection heats the plasma until it escapes");
        assertFalse(p.lit());
        assertEquals(1, p.wear());
        assertTrue(t < 1_000, "within a minute (" + t + " ticks)");
    }

    @Test
    void fadesOnTooLittleFuel() {
        Plasma p = core();
        Buffer b = new Buffer(6_016_000);
        Plasma.Event ev = Plasma.Event.NONE;
        int t = 0;
        while (ev != Plasma.Event.IGNITED) {
            ev = step(p, b, 15);
            t++;
        }
        while (ev != Plasma.Event.FADED && t++ < 3_000) {
            ev = step(p, b, 4);
        }
        assertEquals(Plasma.Event.FADED, ev, "too little fuel lets it cool and go out");
        assertEquals(0, p.wear(), "fading wears nothing");
    }

    @Test
    void feedbackOnPressureHoldsIt() {
        Plasma p = core();
        Buffer b = new Buffer(6_016_000);
        int rate = 15;
        int[] seen = new int[3];
        long lit = 0;
        long rf = 0;
        for (int t = 0; t < 3_000; t++) {
            // The probe's reading reaches the controller two ticks later.
            int pressure = seen[0];
            if (pressure > 11) {
                rate = 0;
            } else if (pressure < 8) {
                rate = 15;
            }
            Plasma.Event ev = step(p, b, rate);
            assertTrue(ev != Plasma.Event.ESCAPED && ev != Plasma.Event.FADED, "held at tick " + t + ": " + ev);
            seen[0] = seen[1];
            seen[1] = seen[2];
            seen[2] = p.pressureLevel();
            if (p.lit()) {
                lit++;
                rf += p.lastPower();
            }
        }
        assertTrue(lit > 2_900, "lit nearly the whole time");
        assertTrue(rf / lit > 15_000, "about 20,000 RF a tick, not " + rf / lit);
        assertTrue(b.stored() > 1_000_000, "it pays for its magnets");
    }

    @Test
    void lightsAgainAfterAnEscapeAndBreaksCoilsWhenRepeated() {
        Plasma p = core();
        Buffer b = new Buffer(20_000_000);
        int escapes = 0;
        for (int t = 0; t < 20_000 && escapes < Plasma.escapesPerCoil(); t++) {
            b.receive(20_000_000, false);
            if (step(p, b, 15) == Plasma.Event.ESCAPED) {
                escapes++;
            }
        }
        assertEquals(Plasma.escapesPerCoil(), escapes, "recovers and lights again with fuel and power");
        assertEquals(Plasma.escapesPerCoil(), p.wear(), "the core's frame breaks a coil at this wear");
        p.coilBroke();
        assertEquals(0, p.wear());
    }

    @Test
    void fieldIsLimitedByCoilsAndSavesRoundTrip() {
        Plasma p = new Plasma();
        p.setCoils(1);
        p.addField(500);
        p.tick(new Buffer(0));
        assertEquals(Plasma.fieldPerCoil(), p.field(), "one coil holds 60 field");
        Plasma q = new Plasma();
        q.load(p.save());
        assertEquals(p.field(), q.field());
        assertEquals(p.lit(), q.lit());
    }
}
