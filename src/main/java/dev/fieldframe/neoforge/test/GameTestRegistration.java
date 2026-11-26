package dev.fieldframe.neoforge.test;

import dev.fieldframe.neoforge.Fieldframe;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 26.1 GameTests are registry entries: each test body is a {@code test_function} and each test an instance bound to
 * an environment and a structure. The same test bodies as the other editions are registered here; the KubeJS
 * example-pack tests are only registered when KubeJS is loaded.
 */
public final class GameTestRegistration {
    private GameTestRegistration() {
    }

    private record Entry(String name, int maxTicks, Consumer<GameTestHelper> body, boolean kubejs, boolean isolated, String structure) {
        Entry(String name, int maxTicks, Consumer<GameTestHelper> body, boolean kubejs, boolean isolated) {
            this(name, maxTicks, body, kubejs, isolated, "empty7");
        }

        Entry(String name, int maxTicks, Consumer<GameTestHelper> body, boolean kubejs) {
            this(name, maxTicks, body, kubejs, false);
        }
    }

    private static final DeferredRegister<Consumer<GameTestHelper>> FUNCTIONS = DeferredRegister.create(Registries.TEST_FUNCTION, Fieldframe.MOD_ID);
    private static final List<Entry> TESTS = new ArrayList<>();

    static {
        TESTS.add(new Entry("adjacent_chest_automation", 600, FieldframeGameTests::adjacentChestAutomation, false));
        TESTS.add(new Entry("hopper_feeds_passive_port_only", 200, FieldframeGameTests::hopperFeedsPassivePortOnly, false));
        TESTS.add(new Entry("energy_between_chassis_via_capability", 100, FieldframeGameTests::energyBetweenChassisViaCapability, false));
        TESTS.add(new Entry("redstone_round_trip", 100, FieldframeGameTests::redstoneRoundTrip, false));
        TESTS.add(new Entry("persistence_round_trip", 400, FieldframeGameTests::persistenceRoundTrip, false));
        TESTS.add(new Entry("breaking_drops_contents_once", 60, FieldframeGameTests::breakingDropsContentsOnce, false));
        TESTS.add(new Entry("transaction_abort_restores_chassis", 40, FieldframeGameTests::transactionAbortRestoresChassis, false));
        TESTS.add(new Entry("blueprint_capture_and_staged_build", 100, FieldframeGameTests::blueprintCaptureAndStagedBuild, false));
        TESTS.add(new Entry("breaker_uses_real_loot_and_tool", 200, WorldModuleGameTests::breakerUsesRealLootAndTool, false));
        TESTS.add(new Entry("breaker_refuses_wrong_tool", 100, WorldModuleGameTests::breakerRefusesWrongTool, false));
        TESTS.add(new Entry("breaker_honours_protection_event", 100, WorldModuleGameTests::breakerHonoursProtectionEvent, false));
        TESTS.add(new Entry("excavator_digs_and_stops_at_bedrock", 300, WorldModuleGameTests::excavatorDigsAndStopsAtBedrock, false));
        TESTS.add(new Entry("placer_places_one_block", 100, WorldModuleGameTests::placerPlacesOneBlock, false));
        TESTS.add(new Entry("crop_tender_harvests_and_replants", 100, WorldModuleGameTests::cropTenderHarvestsAndReplants, false));
        TESTS.add(new Entry("pump_and_bucket_head", 150, WorldModuleGameTests::pumpAndBucketHead, false));
        TESTS.add(new Entry("ejector_and_collector_conserve", 200, WorldModuleGameTests::ejectorAndCollectorConserve, false));
        TESTS.add(new Entry("assembler_crafts_planks", 100, WorldModuleGameTests::assemblerCraftsPlanks, false));
        TESTS.add(new Entry("tank_panel_bucket", 40, WorldModuleGameTests::tankPanelBucket, false));
        TESTS.add(new Entry("empty_hand_click_on_chassis", 40, WorldModuleGameTests::emptyHandClickOnChassis, false));
        TESTS.add(new Entry("owner_gates_editing", 40, WorldModuleGameTests::ownerGatesEditing, false));
        TESTS.add(new Entry("ender_tank_forms_and_charges_rf_per_mb", 100, FeatureGameTests::enderTankFormsAndChargesRfPerMb, false));
        TESTS.add(new Entry("charge_pad_charges_item_on_ground", 100, FeatureGameTests::chargePadChargesItemOnGround, false));
        TESTS.add(new Entry("health_pad_heals_and_pays", 400, FeatureGameTests::healthPadHealsAndPays, false));
        // Fundamentum gear is always registered.
        TESTS.add(new Entry("electric_whips_pay_per_swing_and_charge", 100,
                dev.fieldframe.neoforge.compat.fundamentum.WhipGameTests::whipsPayPerSwingAndCharge, false));
        TESTS.add(new Entry("graviton_hook_is_a_fundamentum_hook", 20,
                dev.fieldframe.neoforge.compat.fundamentum.WhipGameTests::gravitonHookIsAFundamentumHook, false));

        if (net.neoforged.fml.ModList.get().isLoaded("ftbchunks")) {
            // Run with -PwithClaims: a real claim mod refuses a stranger's machine.
            TESTS.add(new Entry("breaker_refused_in_ftb_chunks_claim", 100, ClaimGameTests::breakerRefusedInFtbChunksClaim, false, true));
        }
        TESTS.add(new Entry("exchanger_swaps_rings_and_conserves", 200, FeatureGameTests::exchangerSwapsRingsAndConserves, false));
        TESTS.add(new Entry("mixer_fluid_then_infuser_fills_armor", 400, FeatureGameTests::mixerFluidThenInfuserFillsArmor, false));
        TESTS.add(new Entry("flora_armor_grants_effect", 40, FeatureGameTests::floraArmorGrantsEffect, false));
        TESTS.add(new Entry("fluid_armor_every_fluid_has_an_effect", 20, FluidArmorGameTests::everyFluidHasAnEffect, false));
        TESTS.add(new Entry("fluid_armor_jei_rows_without_jei", 20, FluidArmorGameTests::jeiRowsWithoutJei, false));
        TESTS.add(new Entry("fluid_armor_worn_fluids_give_effects", 20, FluidArmorGameTests::wornFluidsGiveEffects, false));
        TESTS.add(new Entry("fluid_armor_infuser_takes_table_fluids", 200, FluidArmorGameTests::infuserTakesTableFluids, false));
        TESTS.add(new Entry("fluid_buckets_place_and_pick_up", 40, PlaceableFluidGameTests::bucketsPlaceAndPickUp, false));
        TESTS.add(new Entry("fluid_hot_flows_slow_cold_flows_fast", 120, PlaceableFluidGameTests::hotFlowsSlowColdFlowsFast, false));
        TESTS.add(new Entry("flora_potion_effects", 20, VerificationGameTests::floraPotionEffects, false, false));
        TESTS.add(new Entry("flora_air_antidote_and_food", 20, VerificationGameTests::floraAirAntidoteAndFood, false, false));
        TESTS.add(new Entry("flora_max_health_flux_is_removable", 20, VerificationGameTests::floraMaxHealthFluxIsRemovable, false, false));
        TESTS.add(new Entry("flora_pulses_hit_nearby_creature", 20, VerificationGameTests::floraPulsesHitNearbyCreature, false, true));
        TESTS.add(new Entry("flora_self_combustion_breaks_no_blocks", 20, VerificationGameTests::floraSelfCombustionBreaksNoBlocks, false, true));
        TESTS.add(new Entry("flora_escape_teleport_when_badly_hurt", 20, VerificationGameTests::floraEscapeTeleportWhenBadlyHurt, false, true));
        TESTS.add(new Entry("flora_fire_protection_and_attacker_teleport", 20, VerificationGameTests::floraFireProtectionAndAttackerTeleport, false, true));
        TESTS.add(new Entry("flora_attack_ignites_and_fall_rules", 20, VerificationGameTests::floraAttackIgnitesAndFallRules, false, true));
        TESTS.add(new Entry("flora_cold_damage_only_in_the_cold", 20, VerificationGameTests::floraColdDamageOnlyInTheCold, false, true));
        TESTS.add(new Entry("flora_pacify_turns_mobs_on_each_other", 20, VerificationGameTests::floraPacifyTurnsMobsOnEachOther, false, true));
        TESTS.add(new Entry("flora_burning_target_explodes", 20, VerificationGameTests::floraBurningTargetExplodes, false, true));
        TESTS.add(new Entry("flora_key_actions", 40, VerificationGameTests::floraKeyActions, false, true));
        TESTS.add(new Entry("exchanger_protection_creative_and_radius_keys", 200, VerificationGameTests::exchangerProtectionCreativeAndRadiusKeys, false, false));
        TESTS.add(new Entry("tank_glass_connects", 20, VerificationGameTests::tankGlassConnects, false, false));
        TESTS.add(new Entry("tank_controller_item_keeps_contents", 60, VerificationGameTests::tankControllerItemKeepsContents, false, false));
        TESTS.add(new Entry("entity_mover_pushes_creature", 100, VerificationGameTests::entityMoverPushesCreature, false, false));
        TESTS.add(new Entry("waterwheel_generates_from_flowing_water", 300, VerificationGameTests::waterwheelGeneratesFromFlowingWater, false, false));
        TESTS.add(new Entry("motion_harvester_generates_from_movement", 200, VerificationGameTests::motionHarvesterGeneratesFromMovement, false, false));
        TESTS.add(new Entry("service_port_limits_aggregate_items", 40, VerificationGameTests::servicePortLimitsAggregateItems, false, false));
        TESTS.add(new Entry("environment_sensor_counts_animals", 60, VerificationGameTests::environmentSensorCountsAnimals, false, false));
        TESTS.add(new Entry("performance_sample", 260, VerificationGameTests::performanceSample, false, true));
        TESTS.add(new Entry("thermal_brewer_brews_with_game_recipes", 300, ThermalGameTests::brewerBrewsWithGameRecipes, false));
        TESTS.add(new Entry("thermal_enchanter_uses_liquid_experience", 300, ThermalGameTests::enchanterUsesLiquidExperience, false));
        TESTS.add(new Entry("thermal_charger_charges_real_item", 200, ThermalGameTests::chargerChargesRealItem, false));
        TESTS.add(new Entry("thermal_fisher_catches_from_real_loot_table", 200, ThermalGameTests::fisherCatchesFromRealLootTable, false));
        TESTS.add(new Entry("thermal_xp_collector_absorbs_orbs", 200, ThermalGameTests::xpCollectorAbsorbsOrbs, false, true));
        TESTS.add(new Entry("thermal_metallurgy_alloys_and_dusts", 400, ThermalGameTests::metallurgyAlloysAndDusts, false));
        TESTS.add(new Entry("thermal_metallurgy_molten_metals", 400, ThermalGameTests::metallurgyMoltenMetals, false));
        TESTS.add(new Entry("thermal_press_makes_plates_and_gears_with_dies", 400, ThermalGameTests::pressMakesPlatesAndGearsWithDies, false));
        TESTS.add(new Entry("thermal_aura_chamber_gives_metallurgy_effect", 200, ThermalGameTests::auraChamberGivesMetallurgyEffect, false, true));
        TESTS.add(new Entry("thermal_casting_nuggets_blocks_and_alloys", 600, ThermalGameTests::castingNuggetsBlocksAndAlloys, false));
        TESTS.add(new Entry("thermal_tier_kit_upgrades_chassis_and_is_returned", 100, ThermalGameTests::tierKitUpgradesChassisAndIsReturned, false));
        TESTS.add(new Entry("thermal_recipe_filter_removes_metallurgy_recipe", 400, ThermalGameTests::recipeFilterRemovesMetallurgyRecipe, false, true));
        TESTS.add(new Entry("machine_sawmill_cuts_logs", 300, MachineGameTests::sawmillCutsLogs, false));
        TESTS.add(new Entry("machine_insolator_grows_crops", 700, MachineGameTests::insolatorGrowsCrops, false));
        TESTS.add(new Entry("machine_centrifuge_separates_gravel", 300, MachineGameTests::centrifugeSeparatesGravel, false));
        TESTS.add(new Entry("machine_heater_ore_smelting_gives_slag", 100, MachineGameTests::heaterOreSmeltingGivesSlag, false));
        TESTS.add(new Entry("machine_transposer_empties_bucket", 200, MachineGameTests::transposerEmptiesBucket, false));
        TESTS.add(new Entry("machine_refinery_refines_sap", 400, MachineGameTests::refineryRefinesSap, false));
        TESTS.add(new Entry("machine_pyrolyzer_makes_charcoal", 500, MachineGameTests::pyrolyzerMakesCharcoal, false));
        TESTS.add(new Entry("machine_precipitator_freezes_water", 400, MachineGameTests::precipitatorFreezesWater, false));
        TESTS.add(new Entry("machine_extruder_makes_cobblestone", 300, MachineGameTests::extruderMakesCobblestone, false));
        TESTS.add(new Entry("machine_data_engines_burn_fuel", 400, MachineGameTests::dataEnginesBurnFuel, false));
        TESTS.add(new Entry("machine_food_and_arcane_engines_burn", 400, MachineGameTests::foodAndArcaneEnginesBurn, false));
        TESTS.add(new Entry("machine_nullifier_deletes_only_when_armed", 100, MachineGameTests::nullifierDeletesOnlyWhenArmed, false));
        TESTS.add(new Entry("machine_water_collector_fills_tank", 200, MachineGameTests::waterCollectorFillsTank, false));
        TESTS.add(new Entry("machine_tree_tapper_collects_sap", 300, MachineGameTests::treeTapperCollectsSap, false));
        TESTS.add(new Entry("machine_fluid_port_imports_from_neighbour", 200, MachineGameTests::fluidPortImportsFromNeighbour, false));
        TESTS.add(new Entry("machine_controller_program_drives_channel", 100, MachineGameTests::controllerProgramDrivesChannel, false));
        TESTS.add(new Entry("machine_port_sends_fuel_to_engine", 900, MachineGameTests::portSendsFuelToEngine, false));
        TESTS.add(new Entry("machine_comparator_reads_green_storage", 100, MachineGameTests::comparatorReadsGreenStorage, false));
        TESTS.add(new Entry("machine_working_face_syncs_to_clients", 300, MachineGameTests::workingFaceSyncsToClients, false));
        TESTS.add(new Entry("machine_indicator_gauge_syncs", 300, MachineGameTests::indicatorGaugeSyncsToClients, false));
        TESTS.add(new Entry("multiplayer_two_players_edit_one_machine", 40, MachineGameTests::twoPlayersEditOneMachineInOneTick, false, true));
        TESTS.add(new Entry("machine_program_card_copies_and_pastes", 40, MachineGameTests::programCardCopiesAndPastes, false));
        TESTS.add(new Entry("power_hand_crank_turns_for_the_player", 100, MachineGameTests::handCrankTurnsForThePlayer, false));
        TESTS.add(new Entry("field_kit_installs_and_cranks_in_hand", 120, MachineGameTests::fieldKitInstallsAndCranks, false));
        TESTS.add(new Entry("machine_sounds_and_advancements_loaded", 40, MachineGameTests::soundsAndAdvancementsLoaded, false));
        TESTS.add(new Entry("power_steam_loop_runs_on_coal", 400, MachineGameTests::steamLoopRunsOnCoal, false));
        TESTS.add(new Entry("power_boiler_runs_on_magma_heat", 200, MachineGameTests::boilerRunsOnMagmaHeat, false));
        TESTS.add(new Entry("power_thermocouple_magma_against_blue_ice", 200, MachineGameTests::thermocoupleMagmaAgainstBlueIce, false));
        TESTS.add(new Entry("power_wind_rotor_needs_height", 200, MachineGameTests::windRotorNeedsHeight, false));
        TESTS.add(new Entry("power_storm_coil_catches_lightning", 200, MachineGameTests::stormCoilCatchesLightning, false));
        TESTS.add(new Entry("power_blast_damper_catches_tnt", 200, MachineGameTests::blastDamperCatchesTnt, false));
        TESTS.add(new Entry("power_galvanic_cell_runs_on_iron_and_copper", 200, MachineGameTests::galvanicCellRunsOnIronAndCopper, false));
        TESTS.add(new Entry("power_lunar_prism_follows_the_night", 200, MachineGameTests::lunarPrismFollowsTheNight, false));
        // Isolated: probes within 24 blocks share ground heat, so a neighbouring test's probe skews the ratio.
        TESTS.add(new Entry("power_geothermal_probe_feels_magma_below", 400, MachineGameTests::geothermalProbeFeelsMagmaBelow, false, true));
        TESTS.add(new Entry("power_treadmill_cow_walks_for_wheat", 300, MachineGameTests::treadmillCowWalksForWheat, false));
        TESTS.add(new Entry("power_bio_digester_feeds_a_fluid_engine", 900, MachineGameTests::bioDigesterFeedsAFluidEngine, false));
        TESTS.add(new Entry("machine_storage_sorter_moves_by_filter", 100, MachineGameTests::storageSorterMovesByFilter, false));
        TESTS.add(new Entry("machine_tag_unifier_passes_items_on", 100, MachineGameTests::tagUnifierPassesItemsOn, false));
        TESTS.add(new Entry("machine_tool_repairer_mends_with_experience", 200, MachineGameTests::toolRepairerMendsWithExperience, false));
        TESTS.add(new Entry("machine_mob_grinder_kills_and_collects", 400, MachineGameTests::mobGrinderKillsAndCollects, false, true));
        TESTS.add(new Entry("bolt_chassis_share_storage", 400, MachineGameTests::boltedChassisShareStorage, false));
        TESTS.add(new Entry("bolt_orphaned_part_recovers", 100, MachineGameTests::orphanedBoltPartRecovers, false));
        TESTS.add(new Entry("perf_baseline", 320, PerfGameTests::perfBaseline, false, true, "perf24"));
        TESTS.add(new Entry("perf_100", 320, PerfGameTests::perf100, false, true, "perf24"));
        TESTS.add(new Entry("perf_500", 320, PerfGameTests::perf500, false, true, "perf24"));
        for (KubeJSExampleGameTests.Named n : KubeJSExampleGameTests.generate()) {
            TESTS.add(new Entry(n.name(), n.timeout(), n.body(), true));
        }
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(Fieldframe.MOD_ID, path);
    }

    public static void register(IEventBus bus) {
        for (Entry e : TESTS) {
            FUNCTIONS.register(e.name(), () -> e.body());
        }
        FUNCTIONS.register(bus);
        bus.addListener(GameTestRegistration::onRegisterTests);
    }

    private static void onRegisterTests(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> env = event.registerEnvironment(id("default"), new TestEnvironmentDefinition.AllOf(List.of()));
        boolean kubejs = ModList.get().isLoaded("kubejs");
        for (Entry e : TESTS) {
            if (e.kubejs() && !kubejs) {
                continue;
            }
            // Tests acting on everything within 20 blocks get their own environment, so they run in their own batch.
            Holder<TestEnvironmentDefinition<?>> testEnv = e.isolated()
                    ? event.registerEnvironment(id("isolated_" + e.name()), new TestEnvironmentDefinition.AllOf(List.of())) : env;
            event.registerTest(id(e.name()), new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, id(e.name())),
                    new TestData<>(testEnv, id(e.structure()), e.maxTicks(), 0, true)));
        }
    }
}
