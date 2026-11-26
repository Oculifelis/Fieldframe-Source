package dev.fieldframe.core;

import dev.fieldframe.api.FieldframeRegistry;
import dev.fieldframe.api.data.FfData;
import dev.fieldframe.api.face.LocalFace;
import dev.fieldframe.core.chassis.ChassisCore;
import dev.fieldframe.core.chassis.SimulatedWorld;
import dev.fieldframe.core.controller.ControllerProgram;
import dev.fieldframe.core.controller.ControllerVM;
import dev.fieldframe.core.modules.BuiltinModules;
import dev.fieldframe.core.modules.ControllerBehavior;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControllerTest {
    /** Mutable fake inputs. */
    static final class In implements ControllerVM.Inputs {
        long[] items = new long[3];
        long[] fluid = new long[3];
        long power;
        int[] ch = new int[8];

        public long items(int l) {
            return items[l];
        }

        public long itemCapacity(int l) {
            return 256;
        }

        public long fluid(int l) {
            return fluid[l];
        }

        public long fluidCapacity(int l) {
            return 4000;
        }

        public long power() {
            return power;
        }

        public long powerCapacity() {
            return 16000;
        }

        public int channel(int i) {
            return ch[i];
        }
    }

    static ControllerVM vm(String src) throws ControllerProgram.ParseException {
        return new ControllerVM(ControllerProgram.parse(src));
    }

    @Test
    void parsesTheDesignExampleAsAProgram() throws Exception {
        ControllerVM vm = vm("""
                # Example from the design document, written in Controller syntax
                states fill, drain
                reg batch_count
                in fill: when fluid a < 1000 then set ch 1 = 15
                in fill: when item b >= 16 and power >= 25% then set ch 2 = 15
                in fill: when rise ch 3 then inc batch_count
                in fill: when batch_count >= 8 then goto drain, reset batch_count
                in drain: when fluid a > 3000 then set ch 4 = 15
                in drain: when fluid a == 0 then goto fill
                """);
        In in = new In();
        in.fluid[0] = 500;
        in.items[1] = 20;
        in.power = 8000;
        ControllerVM.Result r = vm.evaluate(in);
        assertTrue(r.ok());
        assertEquals(15, r.channelWrites()[0]);
        assertEquals(15, r.channelWrites()[1]);
        assertEquals(-1, r.channelWrites()[3]);
        // 8 rising edges on ch 3 -> state drain.
        for (int i = 0; i < 8; i++) {
            in.ch[2] = 15;
            vm.evaluate(in);
            in.ch[2] = 0;
            vm.evaluate(in);
        }
        assertEquals("drain", vm.stateName());
        assertEquals(0, vm.register("batch_count"), "goto and reset in the same rule");
    }

    @Test
    void snapshotReadsAndFirstWriterWinsForRegisters() throws Exception {
        ControllerVM vm = vm("""
                reg x
                when always then x = 5
                when x == 5 then x = 9
                when always then inc x
                """);
        ControllerVM.Result r = vm.evaluate(new In());
        assertEquals(5, vm.register("x"), "rule 1 wins; rule 2 reads the old value 0; rule 3 conflicts");
        assertEquals(1, r.ignoredConflicts());
        vm.evaluate(new In());
        assertEquals(5, vm.register("x"), "rule 2 now matches but rule 1 still has priority");
    }

    @Test
    void channelWritesTakeTheMaximum() throws Exception {
        ControllerVM vm = vm("when always then set ch 1 = 3\nwhen always then set ch 1 = 12\nwhen always then set ch 1 = 7");
        assertEquals(12, vm.evaluate(new In()).channelWrites()[0]);
    }

    @Test
    void registersSaturate() throws Exception {
        ControllerVM vm = vm("reg r\nwhen always then r = 2147483647\nin run: when r == 2147483647 then inc r");
        vm.evaluate(new In());
        vm.evaluate(new In());
        assertEquals(Integer.MAX_VALUE, vm.register("r"));
    }

    @Test
    void edgesUsePreviousEvaluationSnapshot() throws Exception {
        ControllerVM vm = vm("reg n\nwhen fall ch 2 then inc n");
        In in = new In();
        in.ch[1] = 15;
        vm.evaluate(in);
        vm.evaluate(in);
        in.ch[1] = 0;
        vm.evaluate(in);
        vm.evaluate(in);
        assertEquals(1, vm.register("n"));
    }

    @Test
    void budgetAbortsWithoutCommitting() throws Exception {
        StringBuilder sb = new StringBuilder("reg x\n");
        for (int i = 0; i < 32; i++) {
            sb.append("when ch 1 > 0 or ch 2 > 0 or ch 3 > 0 or ch 4 > 0 or ch 5 > 0 or ch 6 > 0 or ch 7 > 0 or always then inc x\n");
        }
        ControllerVM vm = vm(sb.toString());
        ControllerVM.Result r = vm.evaluate(new In());
        assertFalse(r.ok());
        assertTrue(r.fault().startsWith("BUDGET"));
        assertEquals(0, vm.register("x"));
    }

    @Test
    void parseErrorsCarryLineNumbersAndLimits() {
        ControllerProgram.ParseException e = assertThrows(ControllerProgram.ParseException.class,
                () -> ControllerProgram.parse("when always then set ch 1 = 3\nwhen item d > 3 then set ch 1 = 1"));
        assertEquals(2, e.line);
        assertThrows(ControllerProgram.ParseException.class, () -> ControllerProgram.parse("when always then set ch 9 = 1"));
        assertThrows(ControllerProgram.ParseException.class, () -> ControllerProgram.parse("when always then goto nowhere"));
        assertThrows(ControllerProgram.ParseException.class, () -> ControllerProgram.parse("when always then x = 1"));
        assertThrows(ControllerProgram.ParseException.class, () -> ControllerProgram.parse("when ch 1 > 5% then set ch 1 = 1"));
        assertThrows(ControllerProgram.ParseException.class, () -> ControllerProgram.parse("reg a,b,c,d,e,f,g,h,i"));
        assertThrows(ControllerProgram.ParseException.class, () -> ControllerProgram.parse("when always then set ch 1 = 1; exec('x')"));
        StringBuilder many = new StringBuilder();
        for (int i = 0; i < 33; i++) {
            many.append("when always then set ch 1 = 1\n");
        }
        assertThrows(ControllerProgram.ParseException.class, () -> ControllerProgram.parse(many.toString()));
    }

    @Test
    void controllerPlateDrivesChassisAndPersists() {
        TestSupport.freshRegistry();
        SimulatedWorld w = TestSupport.world();
        ChassisCore c = TestSupport.chassis(w);
        // Default program: stateful hysteresis on RF driving ch 5.
        c.install(LocalFace.FRONT, FieldframeRegistry.module(BuiltinModules.CONTROLLER), null);
        TestSupport.run(c, w, 3);
        assertEquals(15, c.control().read(4), "buffer empty -> charge state drives ch 5");
        c.energy().receive(16_000, false);
        TestSupport.run(c, w, 3);
        assertEquals(0, c.control().read(4));
        c.energy().extract(10_000, false);
        TestSupport.run(c, w, 3);
        assertEquals(0, c.control().read(4), "between thresholds the idle state holds (hysteresis)");
        ChassisCore again = TestSupport.chassis(w);
        assertTrue(again.load(c.save()).isEmpty());
        TestSupport.run(again, w, 2);
        assertEquals("idle", ((ControllerBehavior) again.module(LocalFace.FRONT).behavior()).vm().stateName());
    }

    @Test
    void pauseAndSingleStep() {
        TestSupport.freshRegistry();
        SimulatedWorld w = TestSupport.world();
        ChassisCore c = TestSupport.chassis(w);
        FfData cfg = FieldframeRegistry.module(BuiltinModules.CONTROLLER).defaultConfig()
                .putString("program", "reg n\nwhen always then inc n").putBool("paused", true);
        c.install(LocalFace.FRONT, FieldframeRegistry.module(BuiltinModules.CONTROLLER), cfg);
        TestSupport.run(c, w, 5);
        ControllerBehavior b = (ControllerBehavior) c.module(LocalFace.FRONT).behavior();
        assertEquals(0, b.vm().register("n"));
        c.setConfig(LocalFace.FRONT, c.module(LocalFace.FRONT).config().putLong("step", 1));
        TestSupport.run(c, w, 5);
        assertEquals(1, b.vm().register("n"), "exactly one evaluation per step");
        assertEquals("PAUSED", c.module(LocalFace.FRONT).status().code());
    }

    @Test
    void parseErrorIsAFaultNotACrash() {
        TestSupport.freshRegistry();
        SimulatedWorld w = TestSupport.world();
        ChassisCore c = TestSupport.chassis(w);
        FfData cfg = FieldframeRegistry.module(BuiltinModules.CONTROLLER).defaultConfig().putString("program", "when banana then set ch 1 = 1");
        c.install(LocalFace.FRONT, FieldframeRegistry.module(BuiltinModules.CONTROLLER), cfg);
        TestSupport.run(c, w, 2);
        assertEquals("PARSE_ERROR", c.module(LocalFace.FRONT).status().code());
        assertEquals(0, c.control().read(0));
    }
}
