package dev.fieldframe.core;

import dev.fieldframe.api.data.FfData;
import dev.fieldframe.core.chassis.ChassisCore;
import dev.fieldframe.core.chassis.SimulatedWorld;
import dev.fieldframe.core.controller.ControllerProgram;
import dev.fieldframe.core.controller.ControllerVM;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Save/load round trips for the chassis run state and the controller program state. */
class StateRoundTripTest {
    @BeforeEach
    void setUp() {
        TestSupport.freshRegistry();
    }

    @Test
    void chassisRunStateRoundTrips() {
        SimulatedWorld w = TestSupport.world();
        ChassisCore c = TestSupport.chassis(w);
        assertEquals(ChassisCore.RunState.RUNNING, c.runState());
        c.setPaused(true);
        assertEquals(ChassisCore.RunState.PAUSED, c.runState());
        ChassisCore back = TestSupport.chassis(w);
        back.load(c.save());
        assertEquals(ChassisCore.RunState.PAUSED, back.runState());
        // Waiting for parts wins over the pause toggle, and the toggle survives underneath it.
        back.setAwaitingLink(true);
        assertEquals(ChassisCore.RunState.WAITING_FOR_PARTS, back.runState());
        back.setAwaitingLink(false);
        assertEquals(ChassisCore.RunState.PAUSED, back.runState());
    }

    @Test
    void controllerStateRoundTripsByName() throws Exception {
        String src = """
                states fill, drain
                in fill: when always then goto drain
                """;
        ControllerVM vm = new ControllerVM(ControllerProgram.parse(src));
        vm.evaluate(new ControllerTest.In());
        assertEquals("drain", vm.stateName());
        FfData saved = vm.save();
        ControllerVM back = new ControllerVM(ControllerProgram.parse(src));
        back.load(saved);
        assertEquals("drain", back.stateName());
        // The program was edited before loading: the saved state is found by name, not by index.
        ControllerVM edited = new ControllerVM(ControllerProgram.parse("states drain, idle, fill\nin fill: when always then goto drain"));
        edited.load(saved);
        assertEquals("drain", edited.stateName());
        // A state that no longer exists resets to the first one, as replacing the program does.
        ControllerVM gone = new ControllerVM(ControllerProgram.parse("states a, b\nin a: when always then goto b"));
        gone.load(saved);
        assertEquals("a", gone.stateName());
    }
}
