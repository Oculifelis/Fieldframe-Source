package dev.fieldframe.core;

import dev.fieldframe.api.FieldframeRegistry;
import dev.fieldframe.api.blueprint.Blueprint;
import dev.fieldframe.api.data.FfData;
import dev.fieldframe.api.data.FfJson;
import dev.fieldframe.api.face.LocalFace;
import dev.fieldframe.api.face.Orientation;
import dev.fieldframe.api.face.WorldDir;
import dev.fieldframe.api.id.FfId;
import dev.fieldframe.core.blueprint.BlueprintOps;
import dev.fieldframe.core.chassis.ChassisCore;
import dev.fieldframe.core.chassis.SimulatedWorld;
import dev.fieldframe.core.modules.BuiltinModules;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlueprintTest {
    SimulatedWorld w;
    ChassisCore c;

    @BeforeEach
    void setUp() {
        TestSupport.freshRegistry();
        w = TestSupport.world();
        c = TestSupport.chassis(w);
        c.install(LocalFace.TOP, FieldframeRegistry.module(BuiltinModules.HEATER), null);
        c.install(LocalFace.LEFT, FieldframeRegistry.module(BuiltinModules.ITEM_PORT),
                FieldframeRegistry.module(BuiltinModules.ITEM_PORT).defaultConfig().putString("mode", "import"));
        c.setBinding(LocalFace.TOP, "enable", 3);
        c.lane(0).insert(TestSupport.IRON_ORE, 20, false);
        c.energy().receive(5000, false);
        c.installUpgrade(0, FieldframeRegistry.upgrade(FfId.ff("storage_upgrade")));
    }

    @Test
    void captureContainsConstructionButNoContents() {
        Blueprint bp = BlueprintOps.capture("Smelter", List.of(new BlueprintOps.Source(0, 0, 0, c)));
        String json = FfJson.toJsonString(bp.toData());
        assertFalse(json.contains("raw_iron"), "no lane contents");
        assertFalse(json.contains("5000"), "no RF");
        assertTrue(json.contains("fieldframe:heater"));
        assertTrue(json.contains("storage_upgrade"));
        Map<FfId, Integer> bom = bp.billOfMaterials(FfId.ff("chassis"));
        assertEquals(1, bom.get(FfId.ff("chassis")));
        assertEquals(1, bom.get(BuiltinModules.HEATER));
        assertEquals(1, bom.get(FfId.ff("storage_upgrade")));
    }

    @Test
    void roundTripThroughJsonAndApplyConfigurationOnly() {
        Blueprint bp = BlueprintOps.capture("Smelter", List.of(new BlueprintOps.Source(0, 0, 0, c)));
        Blueprint back = Blueprint.fromData(FfJson.parse(FfJson.toJsonString(bp.toData())));
        ChassisCore target = TestSupport.chassis(w);
        target.install(LocalFace.TOP, FieldframeRegistry.module(BuiltinModules.HEATER), null);
        List<String> before = BlueprintOps.diff(back.chassis().get(0), target);
        assertTrue(before.contains("left: missing fieldframe:item_port"));
        assertTrue(before.contains("top: bindings differ"));
        BlueprintOps.ApplyReport r = BlueprintOps.applyConfiguration(back.chassis().get(0), target);
        assertEquals(1, r.applied());
        assertEquals(1, r.skipped().size(), "never installs the missing port");
        assertEquals(3, target.module(LocalFace.TOP).binding("enable"));
        assertTrue(target.module(LocalFace.LEFT) == null);
        assertEquals(0, target.lane(0).totalCount());
    }

    @Test
    void rotationAndMirrorTransformsAreConsistent() {
        Blueprint.Chassis a = BlueprintOps.captureOne(0, 0, 0, c);
        Blueprint.Chassis b = new Blueprint.Chassis(0, 0, -1, WorldDir.NORTH, a.faces(), List.of());
        Blueprint bp = new Blueprint("Pair", List.of(a, b), null);
        Blueprint r = bp.rotated(1);
        assertEquals(1, r.chassis().get(1).dx());
        assertEquals(0, r.chassis().get(1).dz());
        assertEquals(WorldDir.EAST, r.chassis().get(1).front());
        assertEquals(bp.toData(), bp.rotated(4).toData());
        // Mirror: a port on LEFT (west when facing north) must end up on RIGHT, still pointing west->east mirrored.
        Blueprint m = bp.mirroredX();
        Blueprint.Chassis mc = m.chassis().get(0);
        assertTrue(mc.faces().containsKey(LocalFace.RIGHT));
        assertFalse(mc.faces().containsKey(LocalFace.LEFT));
        Orientation o = new Orientation(mc.front());
        assertEquals(WorldDir.EAST, o.toWorld(LocalFace.RIGHT), "the port now faces east, the mirror image of west");
        assertEquals(bp.toData(), m.mirroredX().toData());
    }

    @Test
    void untrustedInputIsBounded() {
        FfData good = BlueprintOps.capture("x", List.of(new BlueprintOps.Source(0, 0, 0, c))).toData();
        assertThrows(IllegalArgumentException.class, () -> Blueprint.fromData(good.copy().putString("script", "java.lang.Runtime")));
        assertThrows(IllegalArgumentException.class, () -> Blueprint.fromData(good.copy().putInt("format", 99)));
        FfData far = good.copy();
        ((FfData) far.getList("chassis").get(0)).putInt("x", 9999);
        assertThrows(IllegalArgumentException.class, () -> Blueprint.fromData(far));
        FfData badBinding = FfJson.parse(FfJson.toJsonString(good));
        ((FfData) badBinding.getList("chassis").get(0)).getData("faces").getData("top").getData("bindings").putInt("input", 7);
        assertThrows(IllegalArgumentException.class, () -> Blueprint.fromData(badBinding));
        StringBuilder deep = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            deep.append("{\"a\":");
        }
        deep.append("1");
        for (int i = 0; i < 40; i++) {
            deep.append("}");
        }
        assertThrows(IllegalArgumentException.class, () -> FfJson.parse(deep.toString()));
        StringBuilder many = new StringBuilder("{\"format\":1,\"name\":\"x\",\"chassis\":[");
        for (int i = 0; i < 17; i++) {
            many.append(i == 0 ? "" : ",").append("{\"x\":").append(i % 8).append(",\"y\":").append(i / 8).append(",\"z\":0,\"front\":\"north\"}");
        }
        many.append("]}");
        assertThrows(IllegalArgumentException.class, () -> Blueprint.fromData(FfJson.parse(many.toString())));
    }

    @Test
    void forgedConfigInBlueprintIsSanitizedOnDecode() {
        FfData d = BlueprintOps.capture("x", List.of(new BlueprintOps.Source(0, 0, 0, c))).toData();
        FfData leftCfg = ((FfData) d.getList("chassis").get(0)).getData("faces").getData("left").getData("config");
        leftCfg.putLong("batch", 1_000_000).putString("mode", "teleport");
        Blueprint b = Blueprint.fromData(d);
        FfData cfg = b.chassis().get(0).faces().get(LocalFace.LEFT).config();
        assertEquals(64, cfg.getLong("batch", 0));
        assertEquals("passive_both", cfg.getString("mode", ""));
    }

    @Test
    void compatibilityReportsUnknownModules() {
        FfData d = BlueprintOps.capture("x", List.of(new BlueprintOps.Source(0, 0, 0, c))).toData();
        ((FfData) d.getList("chassis").get(0)).getData("faces").getData("top").putString("module", "otheraddon:gizmo");
        Blueprint b = Blueprint.fromData(d);
        assertTrue(b.compatibilityProblems().contains("unknown module otheraddon:gizmo on top"));
    }
}
