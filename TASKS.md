# TASKS

Living task list. A box is ticked only when the work is done **and** verified by the command or test named next to
it. Editions: F = Forge 1.20.1, N1 = NeoForge 1.21.1, N26 = NeoForge 26.1.2 (tested on 26.1.2.117; KubeJS on .108).

## 1. Engineer's Toolbox (Fieldframe core)
- [x] Core, handling, processing, generation, control, controller, blueprints, manual. Verified by common
      `./gradlew test` and GameTests on F/N1/N26 (see `docs/test_report.md`).
- [x] World family (Breaker, Placer, Interaction Head, Crop Tender, Entity Mover, Hydraulic Excavator), Pump,
      Collector, Ejector, Combined Service Port, Recipe Assembler, Waterwheel/Turbine, Motion Harvester. Verified by
      unit tests and 11 world GameTests per edition.
- [x] Java addon examples on all editions (`checkApiOnly` + 2/2 GameTests each).
- [x] In-world tests for Entity Mover, Motion Harvester, Waterwheel, Service Port, Environment Sensor entity mode (VerificationGameTests, 3 editions)
- [x] In-game performance sample: 49 working chassis, 0.12-0.28 ms per server tick, ~650 bytes saved each (docs/performance.md)
- [x] Two-process multiplayer session: dedicated server + owner client + guest client, PASS on F/N1/N26 (`scripts/multiplayer-smoke.sh`; EULA accepted by the user 2026-10-15 for local test servers): controller edit, blueprint build, guest refused
- [x] In-game perf at 0/100/500 chassis + network bytes on F/N1/N26 (`PerfGameTests`, docs/performance.md): 500 chassis = 1.3-2.2 ms/tick
- [x] Recipe Assembler ghost-grid pattern editor (F/N1/N26; round-trip unit test + client smoke)
- [x] World-action veto event (Java + KubeJS `worldAction`) and read-only KubeJS `upgrades()` / `blueprint(json)` (unit + KubeJS GameTests, 3 editions)

## 2. Research (EnderTech, FLORA, FLORA Reborn)
- [x] Original mods identified with sources and versions. EnderTech 0.3.2 (Sky Welch / voxelcarrot); FLORA 1.0.5
      (pixlepix); FLORA Reborn 1.0.8 / 2.0 (dmf444). Not a plant mod and not an API library. See
      `docs/features/endertech.md` and `docs/features/flora.md`.
- [x] Inventory of items, blocks, machines, mechanics and recipes, from source
- [x] FLORA Reborn additions identified by git diff (R1–R5)
- [~] FLORA Reborn 1.11.2 `2.0`: changelog says only "Updated to 1.11.2!"; jar not opened (needs a download)
- [x] Resolved: Charge Pad distance loss existed 2014-08-14 to 2014-09-09 (`17bca32`..`3e28449`) and is absent from the final release
- [x] Resolved: tank multiplier 64 → 128 → 512 during 2014 (`be8715f`, `ccdcd39`, `14b4bae`); final 512 used

## 3. Licenses and provenance
- [x] Licenses checked per mod, code and assets separately, in `docs/provenance.md`:
  - EnderTech: code ISC, art CC BY-NC-ND 4.0.
  - FLORA and FLORA Reborn: MIT on CurseForge only, with no LICENSE file in either repository.
- [x] No code or texture reuse. All mechanics are reimplemented; all textures are created by
      `tools/gen_feature_textures.py` (new).
- [x] Attribution in `CREDITS.md` and `docs/provenance.md`

## 4. EnderTech implementation (namespace `fieldframe`)
| Feature | common | F | N1 | N26 | tests |
| --- | --- | --- | --- | --- | --- |
| Exchanger (3 tiers) | [x] | [x] | [x] | [x] | unit + `exchanger_swaps_rings_and_conserves` |
| Charge Pad (3 tiers) | [x] | [x] | [x] | [x] | unit + `charge_pad_charges_item_on_ground` |
| Health Pad (3 tiers) | [x] | [x] | [x] | [x] | unit + `health_pad_heals_and_pays` |
| Ender Tank multiblock | [x] | [x] | [x] | [x] | unit + `ender_tank_forms_and_charges_rf_per_mb` |
| Manual chapter | [x] | | | | ShippedManualTest + client smoke |
Also verified in-world (VerificationGameTests): radius-key logic, Creative tier, protection skip, controller item keeping tank contents.

## 5. FLORA (+ Reborn) implementation (namespace `fieldframe`)
| Feature | common | F | N1 | N26 | tests |
| --- | --- | --- | --- | --- | --- |
| 7 effect fluids + buckets | [x] | [x] | [x] | [x] | `mixer_fluid_then_infuser_fills_armor` |
| Fluid armor 4×4 | [x] | [x] | [x] | [x] | same + `flora_armor_grants_effect` |
| Armor Infuser module | [x] | [x] | [x] | [x] | unit + same GameTest |
| Interaction matrix + 28 effects | [x] | [x] | [x] | [x] | unit; in-world 25 of 28 (cold damage unit-only; pacify, explode-burning not asserted) |
| Key actions (blink, sink, fireball) | [x] | [x] | [x] | [x] | `flora_key_actions` (server side; client key press not exercised) |
| Manual chapter | [x] | | | | ShippedManualTest + client smoke |

## 6. Materials and recipe integration
- [x] Ender Alloy ingot (Mixer: gold + 2 ender pearls + lava) is the shared late-game material
- [x] Effect fluids made in Fieldframe's Mixer from vanilla items + water/lava
- [x] Recipes use Fieldframe parts instead of Thermal Expansion: chassis, Cell Bay Upgrade, Tank Bay Upgrade,
      Fluid Port, Power Terminal, blank module. Tables are in both feature files.
- [x] Recipe graph cycle check passes (`tools/gen_resources.py`)
- [x] Integration: the Armor Infuser is a chassis module (lanes, reservoirs, RF, automation); the Ender Tank valve
      works with Fieldframe Fluid Ports; the Charge Pad charges the Exchanger.

## 7. Blockers
- None. Open questions are listed under Research.

## 8. Validation log (2026-10-14, this machine)
JDK 21 for F/N1, JDK 25 for N26. Each command was run from the named directory as `./gradlew <task>`.
| Command | Result |
| --- | --- |
| `common: test` | pass (all unit tests, including the 10 EnderTech/FLORA tests) |
| `forge-1.20.1: build` | BUILD SUCCESSFUL: main, `-api` and `-sources` jars |
| `neoforge-1.21.1: build` | BUILD SUCCESSFUL: 3 jars |
| `neoforge-26.1.2: build` | BUILD SUCCESSFUL: 3 jars |
| `forge-1.20.1: runGameTestServer` | 24/24, re-run 2026-10-15 with verification tests: 41/41 |
| `neoforge-1.21.1: runGameTestServer` | 24/24, re-run: 41/41 |
| `neoforge-26.1.2: runGameTestServer` | 26/26, re-run: 43/43 (26.1.2.117) |
| `forge-1.20.1: runClient -PclientSmoke` | 57 screens, 0 render errors, no missing models/textures |
| `neoforge-1.21.1: runClient -PclientSmoke` | 57 screens, 0 errors |
| `neoforge-26.1.2: runClient -PclientSmoke` | 57 screens, 0 errors |
| `forge-1.20.1: runGameTestServer -PwithKubeJS` | 29/29, re-run: 46/46 |
| `neoforge-1.21.1: runGameTestServer -PwithKubeJS` | 29/29, re-run: 46/46 |
| `neoforge-26.1.2: runGameTestServer -PwithKubeJS -Pneo_version=26.1.2.108` | 31/31, re-run: 48/48 |
| `<edition>: publish`, then `examples/java-addon-<edition>: runGameTestServer` | `checkApiOnly` ok; 2/2, 2/2, 28/28 (26.1 also runs Fieldframe's 26) |
Re-run 2026-10-15: common tests, all 3 builds and all 3 client smoke tests (57 screens each, 0 errors) passed again.
| `scripts/multiplayer-smoke.sh` (runServer + runClient -PmpSmoke, --no-daemon) | PASS on forge-1.20.1, neoforge-1.21.1, neoforge-26.1.2 (9 client checks + server counters each) |
Full re-run 2026-10-15 (`--no-daemon`): common test; GameTests 44/44/46; with KubeJS 49/49/51; client smoke 58×3, 0 errors;
addon examples 2/2/28; multiplayer owner+guest PASS ×3; builds ×3. Jars copied to `dist/`.
Not done: manual in-game play, real claim/protection mods (download), opening the FLORA Reborn 2.0 jar (download).

## 9. Thermal-style machines, materials and classic textures (2026-10-17, owner's request)
- [x] Classic EnderTech and FLORA textures as an optional layer (`shared-classic/`, NOTICE + per-file list; EnderTech
      unmodified, non-commercial). Jar contents checked on all editions. Not yet seen in game.
- [x] Materials table `shared/fieldframe_tables/materials.json`: 4 ore metals with worldgen, 6 alloys, plates/gears/dusts,
      10 items, 7 fluids. Registration, models, tags (c:/forge:), loot (fortune, silk touch), recipes and worldgen
      built from it. Compiled on all editions.
- [x] 24 Thermal-style modules (docs/thermal_parity.md): 10 processors on new recipe families, 5 engines with data
      fuels, charger, brewer, enchanter, extruder, nullifier, water collector, fisher, tree tapper, XP collector.
      `ThermalMachinesTest` 13/13; common tests 109/109.
- [x] Configuration: `config/fieldframe-common.toml` ([ores], [machines] disabled) + datapack fuels; docs/configuration.md.
- [x] Manual: new Machine Shop chapter (5 entries), validated by ShippedManualTest.
- [x] Existing GameTests still pass with the new content loaded (2026-10-18): 44 (F), 45 (N1), 47 (N26). The servers
      loaded every new recipe, loot table, tag, fuel file (15 entries) and the ore biome modifier without errors.
- [x] In-world GameTests for the new content (`ThermalGameTests`, 6 per edition, 2026-10-18): brewer with the game's
      brewing recipes, enchanter paid in liquid experience, charger filling a real Exchanger, fisher rolling the real
      fishing loot table, XP collector absorbing an orb, tin ore (biome modifier present + vein in stone and deepslate).
      Totals: 50 (F), 51 (N1), 53 (N26), all pass.
- [x] Client smoke and multiplayer for the new content (hidden desktop, 1.21.1 + 26.1.2). Ores are Metallurgy's now.
- [ ] In-game look at the classic EnderTech/FLORA textures (optional layer).
- [x] In-world checks of brewing, enchanting, charging, fishing and orb collection (ThermalGameTests, 3 editions).

## 10. Metallurgy as the metals provider (2026-10-18, owner's request)
- [x] Fieldframe ores, ore worldgen, ore config switches, tin/lead/silver/nickel, bronze/invar/electrum/constantan,
      vanilla-metal dusts, plates and gears removed. Kept: signal, lumen and ender alloys; non-metal items; fluids.
- [x] Required dependency on `metallurgy` in all three mods.toml files; dev runs load the fork's jar.
- [x] Alloy Smelter mirrors Metallurgy's 19 alloyer recipes, Crusher its 140 crusher recipes (snapshot + conditions).
      Sulfur, potash and bitumen byproducts now Metallurgy's.
- [x] GameTests with Metallurgy loaded: 53/53 (26.1.2, Metallurgy port.14 on NeoForge .122), 51/51 (1.21.1, port.7 on
      21.1.262), including `metallurgy_alloys_and_dusts` (3 copper + 1 Metallurgy tin -> 4 Metallurgy bronze; tin ingot ->
      tin dust). Common tests 109/109.
- [x] Forge 1.20.1 with Metallurgy port.8: the GameTest server starts and all tests pass (the port.7 `BlockLootMixin`
      startup crash is gone). Client not yet checked.
- [x] Plates and gears are Fieldframe's (owner's decision, 2026-10-18): every Metallurgy metal + iron, gold, netherite
      and Fieldframe's alloys, made in the Press with a Plate Die / Gear Die catalyst.

## 12. Metallurgy molten metals, chamber, live recipes (2026-10-18, owner's request)
- [x] Magma Crucible melts Metallurgy metals (ingot/dust 144, nugget 16, block 1,296 mB) into Metallurgy's molten fluids;
      Blast Chiller casts 144 mB into an ingot. All editions, with Metallurgy's content conditions.
- [x] Aura Chamber module runs Metallurgy's chamber recipes plus `fieldframe/aura` datapack data; JEI page on all
      editions; data synced to clients with the fuels.
- [x] NeoForge editions read Metallurgy's alloyer/crusher/chamber recipes live (no regeneration when Metallurgy
      changes). Forge 1.20.1 keeps the snapshot.
- [x] Unit tests 112; GameTests 53 / 54 / 56, all pass.
- [ ] Metallurgy's own data errors in dev logs: `metallurgy:legacy/gadget/rubracium_gauntlet` (stack size 2 > max 1,
      1.21.1) and a `patchouli:shapeless_book_recipe` recipe loaded without Patchouli (Forge). Belong in the fork.
- [ ] Hands-on client check of the new content (JEI aura page, part textures).
- [x] KubeJS GameTests on 26.1.2: KubeJS 8.0.7 runs on NeoForge .122 (103/103 with the example pack, 2026-10-30).
- [x] Java addon examples load Metallurgy in their dev runs (and use NeoForge 21.1.262 / 26.1.2.122 like Fieldframe).

## 11. JEI and Jade (2026-10-18, owner's request)
- [x] JEI on all three editions: a category per process family (16 on 26.1.2, incl. the Metallurgy mirror), engine
      fuels, modules as crafting stations (also smelting/crafting/brewing/fuel), an info page per module.
- [x] Fuels table synced to clients on join and /reload (FuelSync packet, all editions; round trip unit-tested).
- [x] Jade on all three editions: chassis (per face module + status + progress, RF, lanes, reservoirs), pads, Ender Tank.
      Data and client providers are separate (required by Jade for 1.21.6+).
- [x] 26.1.2 in game with JEI 29.47.0.111 + Jade 26.1.12 (multiplayer smoke -PwithViewers, owner + guest PASS):
      FIELDFRAME_JEI categories=16 recipes=235 fuels=15; screenshots of the Alloy Smelter JEI page and the chassis Jade
      tooltip in neoforge-26.1.2/run/screenshots.
- [ ] 1.21.1 and Forge 1.20.1 in game: blocked by Metallurgy. 1.21.1 port.7 client crashes at load (GadgetVisuals
      listens to the abstract InputEvent); Forge port.7 crashes on Forge 47.4.28 (BlockLootMixin). Plugins compile.
- [ ] Metallurgy 26.1.2 port.14: 1,373 "missing textures" warnings (item models point at textures/items/, which the
      26.1 atlas does not read), so many Metallurgy items show the checkerboard, also in JEI.

## 13. Casts, alloy fluids, chassis tiers, overrides, data API (2026-10-18, owner's request)
- [x] Blast Chiller casts by cast: Ingot Cast 144 mB, Nugget Cast 16 mB, Block Cast 1,296 mB (Metallurgy and own alloys).
- [x] Molten signal / lumen / ender alloy fluids with melting and casting recipes.
- [x] Chassis tiers Basic -> Hardened -> Reinforced -> Signalum -> Resonant with upgrade kits (one level per kit, kits
      returned on break): speed for processors and engines (same RF per job), more bays, stacks, reservoir, RF.
      Java `FieldframeRegistry.registerTier`, KubeJS `event.tier`; Jade shows the tier.
- [x] Every recipe overridable and removable: Metallurgy recipes have the same Fieldframe ids on every edition (a real
      recipe with that id wins on NeoForge); `fieldframe/recipe_filters` data, Java and KubeJS removal (synced, JEI too).
- [x] Java + KubeJS API for fuels and Aura Chamber recipes (`MachineData`, `FieldframeEvents.fuels/auras/recipeFilter`,
      `Fieldframe.fuels()/auras()/tiers()`); data files can remove entries.
- [x] Tests: unit (MachineDataAndTiersTest), GameTests 56 / 57 / 59, KubeJS GameTests 64 / 65, addon tests 3 x 3.
- [x] 26.1.2 multiplayer smoke with JEI + Jade, owner client PASS: fuels/auras synced (15 / 14), Hardened kit accepted by
      the server, JEI Alloy Smelter page (screenshot OK) and Aura Chamber page opened.
- [x] Client checks on a hidden desktop (scripts/hidden-desktop-run.ps1: never visible, never takes focus), multiplayer
      smoke with JEI + Jade, owner + guest + server PASS on NeoForge 1.21.1 and 26.1.2: data synced (15 fuels, 14 auras),
      Hardened kit accepted, JEI Alloy Smelter and Aura Chamber pages, Jade "Tier: Hardened" (screenshots in run/screenshots).
- [ ] Forge 1.20.1 client blocked by Metallurgy port.8: `CorpseRenderMixin` (client) uses `@ModifyArgs`, whose synthetic
      `org.spongepowered.asm.synthetic.args.Args$1` Forge cannot load, so Metallurgy's ClientSetup.renderers crashes in
      common_setup. Fix belongs in the Metallurgy fork (replace @ModifyArgs, e.g. with @ModifyArg or MixinExtras).
      Forge JEI bumped to 15.71.0.224 (Metallurgy requires >= .224).
- [ ] Metallurgy 1.21.1 port.8: item/block textures missing (textures in textures/items|blocks, not on the 1.21.1 block
      atlas), as fixed on 26.1.2 in port.15. Fix belongs in the Metallurgy fork.

## 14. Plain machine screen and smart defaults (2026-10-19, owner's request: "a layman won't figure it out")
- [x] Smart defaults: inputs from Orange, results to Green, fuel/tools/held items from Purple (all six engines now
      burn from Purple). A first machine works with no settings; the manual's first-machine page is 5 short steps.
- [x] Storage named by colour everywhere (machine screen, access panel, Jade, manual test bench, statuses, manual).
- [x] New machine screen on all three editions: sides with names, storage contents, tanks, module description, status in
      words with the fix, "Takes from / Results go to" pickers with contents, How it works, Remove, Advanced section.
      Empty hand on a side without an access panel opens it. Status ids shown as item/fluid names.
- [x] Public API `FieldframeForgeApi.setBinding` (used by the addon examples).
- [x] Tests: common unit tests pass; GameTests 65 / 64 (KubeJS) / 59; addon tests 3/3 each; client screenshots of
      the screen (basic, Advanced, empty side) on 1.21.1 and 26.1.2 via the hidden desktop.
- [ ] Forge 1.20.1 screen is compile-checked only (its client is blocked by Metallurgy port.8, see section 13).
- [x] Recipe pattern screen: whole inventory to pick from, item names instead of ids, drag to paint, JEI drag-and-drop
      onto the squares (all three editions); layout sizes itself to the hint text. Its texts had been lost in an
      earlier lang regeneration (6870911) and are restored in tools/gen_lang.py.
- [x] In-world colour rings on chassis sides (owner preferred Thermal-style rings to the first small marks): a thin
      ring on the faceplate's second pixel ring, one section per storage a module uses (tanks paler), in the screen's colours (FaceMarks in common: codes, layout, placement math;
      synced in the chassis client tag; drawn by all three chassis models). Checked in game on 1.21.1 (multiplayer smoke
      screenshot); unit-tested placement.

## 15. Fuel routing, tank defaults, in-world tests for every machine (2026-10-19)
- [x] Fuel that comes in through an Item Port or Combined Service Port goes to the storage the engine burns from
      (Purple by default), unless a machine on the port's storage has a recipe for it (coal for a Pyrolyzer stays) or an
      engine burns from there; a full fuel storage refuses rather than clogging. API: `ModuleBehavior.burns`,
      `ModuleContext.itemsIn` (addon engines opt in). Unit tests FuelRoutingTest (5).
- [x] Default tank connections where recipes need them: Centrifuge takes from the Orange tank, Fluid Transposer takes
      from Orange and fills Green, Pyrolyzer puts creosote into Green (before, their fluid recipes stopped with "none is
      full" until the player connected a tank). Still optional.
- [x] MachineGameTests (16, all three editions): sawmill, insolator, centrifuge, transposer, refinery, pyrolyzer,
      precipitator, extruder, fluid/gem/redstone engines, food/arcane engines, nullifier, water collector, tree tapper,
      fluid port, controller program, and a chest of ore + coal behind one port. GameTests 75 (26.1.2) / 81 (1.21.1 +
      KubeJS) / 80 (Forge + KubeJS).

## 16. Polish: sounds, lit machines, comparators, advancements (2026-10-20)
- [x] Sound events (17) with subtitles; working modules make an occasional sound by kind; install, remove, upgrade and
      tier-kit sounds. Machine screen **♪** mutes one machine (saved). sounds.json points at vanilla events for now.
- [x] Working modules light up: created glow overlays (60, `tools/gen_glow_textures.py`), full-bright cutout quads on
      all three editions, plus particles from the face. Working state syncs only when it changes (40-tick linger).
- [x] Comparator output with a picker (off, a storage, a tank, RF; default Green storage), vanilla container rule.
- [x] Advancement tab (11) from first chassis to Resonant, granted from code for machine events.
- [x] Java presentation hints and KubeJS `glowTexture`, `workSound`, `workParticle`.
- [x] Tests: MachinePolishTest (6), GameTests comparator / working sync / sounds and advancements (3 editions).
- [ ] In-game look and listen on 26.1.2 (glow, particles, sound volume).

## 17. Heat and wind generators (2026-10-20)
- [x] Temperature table (data, Java contributors, KubeJS `temperatures`), read by `WorldActions.temperature` on all
      three editions (block state, lit blocks, fluid types, biome ambient).
- [x] Steam Loop: Boiler (coal or heat in front), Steam Turbine (spin-up / coast, 4 RF/mB), Condenser (90% / 50% water
      back); steam fluid. Thermocouple: Hot and Cold Junction, waste heat from a working Heater (capped).
- [x] Wind Rotor: height, open air, weather, dimension, shared between rotors within 4 blocks.
- [x] `ModuleContext.chassis().peers()`, `ChassisWorld.position()` / `isThundering()`, `SharedSources`.
- [x] Faceplates, animated blade overlays, sounds, recipes, manual page "Heat and Wind".
- [x] Tests: PowerGeneratorsTest (10); GameTests steam loop, boiler on magma, thermocouple, wind height (3 editions);
      KubeJS temperatures (1.20.1, 1.21.1).
- [ ] In-game look at the spinning blades and a tall wind tower on 26.1.2.
- [x] Junction grades (design G2, 2026-10-21): thermoelement metals bound to the Hot Junction raise its output and
      cap by their electrode-potential spread (up to x2); never used up.

## 18. Lightning, blasts and cells (2026-10-20)
- [x] Capacitor for burst energy; Storm Coil (lightning on a real rod, strike and level signals).
- [x] Blast Damper: cancels explosions within 6 blocks and stores 3,500 x power² RF (explosion handler on all three
      editions); TNT chamber on Reinforced chassis.
- [x] Galvanic Cell with an electrode table (data, Java, KubeJS `electrodes`): real potentials for real metals,
      invented ones for Metallurgy's fantasy metals.
- [x] `ModuleContext.chassisTier()`; tags `fieldframe:lightning_rods`, `fieldframe:explosives`, `fieldframe:electrolytes`.
- [x] Tests: BurstAndCellTest (7); GameTests lightning, TNT, iron/copper cell (3 editions); KubeJS electrodes.
- [ ] Real thunderstorm check in game (the GameTest strikes the rod directly).

## 19. Bolted Chassis (2026-10-20)
- [x] `BoltGroup`: shared stores sized to the sum of the parts, conservation on bolt / unbolt / break, max 8 parts,
      relink after loading, pause while a part is unloaded; control bus flips once per tick.
- [x] Frame Bolt item (use to bolt, sneak-use to unbolt), positions in bolt info, linking once a second, orphan and
      missing-part recovery, no modules on touching sides; machine screen and Jade lines.
- [x] Tests: BoltGroupTest (9); GameTests bolt-and-break and orphan recovery (3 editions).
- [ ] In-game look at a 2x2x2 machine on 26.1.2, and a world reload with the parts in different chunks.

## 20. Fieldframe Frameworks addon (2026-10-20/21)
- [x] Frame API in the core (FrameStructure, FrameComponents, MachineHub; ModuleSocketBlock / ModuleSocketBlockEntity,
      ModuleHostClientApi) on all three editions; `FieldframeForgeApi` helpers (hub save/load, world actions, owner,
      process recipes, tags, `setConfig`).
- [x] Grand Frame, Foundry (pool, alloys by ratio; a metal waits for partners still to melt), Gantry (rail ring;
      quarry / farm / pump head), Starcore (plasma model with no self-stable rate; Field Magnet, Plasma Injector, Plasma
      Probe registered through FieldframeRegistry; deuterium from the Centrifuge; explosions a server setting).
- [x] 26.1.2: 9 addon GameTests (frame x4, Foundry, Gantry x2, Starcore held by a controller program for 1,000 ticks,
      Starcore escaping without control) and PlasmaTest (7 unit tests).
- [x] 1.21.1 and Forge 1.20.1 editions derived by `tools/port_frameworks.py` (rules fail loudly when the source changes).
- [x] 1.21.1 and Forge 1.20.1: all 9 addon GameTests pass (2026-10-21).
- [ ] In-game look at a Starcore and a working Gantry.

## 21. Moon, ground and farm power (2026-10-21)
- [x] Lunar Prism (moon phase from the platform), Geothermal Probe (depth, hot blocks below, shared per area),
      Treadmill (animal table: data, Java `addTreadmillContributor`, KubeJS `treadmill`), Bio-Digester (digestion
      family, biogas in the Fluid Engine, compost as bone meal in the Crop Tender).
- [x] Unit tests (FarmAndNightPowerTest); GameTests pass on all three editions (Forge places GameTests underground,
      so its Lunar Prism test checks NO_SKY there).

## 22. Handy modules and screen work (2026-10-21)
- [x] Storage Sorter, Tag Unifier (preferred mods: module setting, Java, KubeJS `unification`), Tool Repairer, Mob
      Grinder (fake player with the weapon's modifiers; never players or tamed animals).
- [x] Filter editor (P5): inventory and JEI ghost entries, allow/deny, tag picker, exact data. Recipes button (P6):
      JEI page of the selected processor. KubeJS service-bay upgrades (P7: `event.upgrade`).
- [x] Unit tests (HandyModulesTest), GameTests (3 editions), client smoke step for the filter editor.
- [x] All three runs green (2026-10-21); the Mob Grinder test pen is closed over the chassis (a hurt cow jumped out).
- [ ] Hands-on look at the filter editor with JEI. Check that item icons show in the filter grid and next to manual
      module pages on 26.1.2: the client smoke (title screen, no world) draws none there, while 1.21.1 does.

## 23. Visual overhaul (2026-10-21, owner's request: "high quality, 16x, painterly")
- [x] `tools/paint.py`: hue-shifted ramps, mottled fills, organic dithering, bevels, insets, glows, wear.
- [x] Shared drawing helpers painted, so every faceplate, block and item generator uses them; faceplates repainted
      with depth and lit lamps; Frameworks blocks painted (`tools/paint_frameworks.py`).
- [x] Ingots, nuggets, dusts, plates, gears: recoloured Painterly templates (owner's decision; credited).
- [x] Buckets: the loaders' fluid-container model over the game's own bucket (follows resource packs).
- [ ] In-game look; second pass on the faceplates the owner points out.
