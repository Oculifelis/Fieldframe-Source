// Fieldframe example pack - server scripts (KubeJS 6 / Forge 1.20.1). Reloadable with /reload.

ServerEvents.recipes(event => {
  // Recipes for the script-defined 'kubejs:smashing' family used by kubejs:smasher.
  event.custom(Fieldframe.recipes.process('kubejs:smashing')
    .input('minecraft:cobblestone', 1)
    .result('minecraft:sand', 1)
    .result('minecraft:flint', 1, 0.25)
    .duration(40).energy(10)
    .json())

  // Override a shipped recipe: washing gravel now uses less water, returns a little water, and needs a
  // catalyst (a shears that is never consumed).
  event.remove({ id: 'fieldframe:washing/gravel' })
  event.custom(Fieldframe.recipes.process('fieldframe:washing')
    .input('minecraft:gravel', 1)
    .catalyst('minecraft:shears')
    .fluidInput('minecraft:water', 50)
    .result('minecraft:flint', 1)
    .result('minecraft:iron_nugget', 1, 0.15)
    .fluidResult('minecraft:water', 25)
    .duration(60).energy(12)
    .json())
})

// Tune built-in modules within their safe ranges. Invalid values reject the whole batch (see server log).
FieldframeEvents.tuning(event => {
  event.tune('fieldframe:fuel_engine', 'rf_per_tick', 60)
  event.tune('fieldframe:solar_collector', 'rf_per_tick', 12)
})

// A pack rule: this pack disables compacting nuggets in the Press. Inputs are untouched when denied, and the
// machine shows CANCELLED_BY_RULE: no_nugget_press in the configurator.
FieldframeEvents.operationStarting(event => {
  if (event.moduleId == 'fieldframe:press' && event.recipeId == 'fieldframe:pressing/iron_ingot') {
    event.deny('no_nugget_press')
  }
})

FieldframeEvents.operationCompleted(event => {
  if (event.moduleId == 'kubejs:smasher') {
    console.info('Smasher finished ' + event.recipeId + ' at ' + event.address)
  }
})

// A world rule: this pack forbids Interaction Heads (no automated right-clicking). The head shows
// PROTECTED and nothing in the world changes. x/y/z are face-local (depth, right, up).
FieldframeEvents.worldAction(event => {
  if (event.moduleId == 'fieldframe:interaction_head' && event.action == 'use') {
    event.deny('no_auto_click')
  }
})

// Machine data after the datapack files (and, on NeoForge, after the Metallurgy recipes Fieldframe reads).
FieldframeEvents.fuels(event => {
  event.item('fieldframe:redstone_engine', 'minecraft:glowstone_dust', 12000)
})

FieldframeEvents.temperatures(event => {
  // Obsidian counts as warm (a Hot Junction against it makes a little power); packed ice is no longer listed.
  event.block('minecraft:obsidian', 450)
  event.remove('minecraft:packed_ice')
})

FieldframeEvents.electrodes(event => {
  // Emeralds work as a Galvanic Cell metal too (an invented potential).
  event.item('minecraft:emerald', -0.25)
})

FieldframeEvents.unification(event => {
  // Tag Unifiers turn common ingots and dusts into vanilla's first, then Metallurgy's.
  event.prefer('minecraft')
})

FieldframeEvents.treadmill(event => {
  // Llamas walk the Treadmill for hay; mules are as quick as their speed allows.
  event.mob('minecraft:llama', 6, 'minecraft:hay_block')
  event.fastMob('minecraft:mule', 45, 'minecraft:wheat')
})

FieldframeEvents.auras(event => {
  // Two gold blocks give Haste II for a minute; carmot blocks no longer give anything.
  event.add('kubejs:golden_haste', 'minecraft:gold_block', 2, 'minecraft:haste', 1200, 1)
  event.remove('fieldframe:metallurgy_carmot')
})

// Remove a recipe from every Fieldframe machine (an id, or a pattern ending in *).
FieldframeEvents.recipeFilter(event => {
  event.remove('fieldframe:crushing/metallurgy/platinum_from_raw')
})

// Replace a Metallurgy recipe that Fieldframe runs: brass in the Alloy Smelter now gives 3 ingots, faster.
// The id is the one Fieldframe uses for it (fieldframe:alloying/metallurgy/<name>) on every edition.
ServerEvents.recipes(event => {
  event.remove({ id: 'fieldframe:alloying/metallurgy/brass' })
  event.custom(Fieldframe.recipes.process('fieldframe:alloying')
    .input('#metallurgy:processing/alloyer/copper', 3)
    .input('#metallurgy:processing/alloyer/zinc', 1)
    .result('metallurgy:brass_ingot', 3)
    .duration(100).energy(32)
    .json()).id('fieldframe:alloying/metallurgy/brass')
})
