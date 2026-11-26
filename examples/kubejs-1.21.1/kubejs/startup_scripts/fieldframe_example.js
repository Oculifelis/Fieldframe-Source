// Fieldframe example pack - startup scripts (KubeJS 7 / NeoForge 1.21.1).
// Startup scripts run before Fieldframe freezes its module registry, so new module items are created here.
// Changing this file requires a game restart (new registry entries cannot be added by /reload).

FieldframeEvents.registerModules(event => {
  // 1) A new processing module that uses the shared processing engine with its own recipe family.
  //    Recipes for 'kubejs:smashing' are added in server_scripts with Fieldframe.recipes.process(...).
  event.processor('kubejs:smasher', 'kubejs:smashing')
    .stage('automation')
    .displayName('Smasher')
    .faceTexture('fieldframe:block/module/crusher')
    .tunable('speed', 0.5, 4, 1.5, 'x')

  // 2) A stateful scripted control module: divides incoming pulses. State persists with the world.
  event.create('kubejs:pulse_divider')
    .category('control')
    .phase('control')
    .signalIn('in')
    .signalOut('out')
    .configInt('divide', 1, 64, 4)
    .displayName('Pulse Divider')
    .faceTexture('fieldframe:block/module/timer_counter_plate')
    .onTick(ctx => {
      const high = ctx.signal('in') > 0
      if (high && !ctx.getBool('last')) {
        ctx.setNumber('count', ctx.getNumber('count') + 1)
      }
      ctx.setBool('last', high)
      const on = Math.floor(ctx.getNumber('count') / ctx.configInt('divide')) % 2 == 1
      ctx.emit('out', on ? 15 : 0)
      ctx.setStatus(on ? 'SIGNAL_ON' : 'SIGNAL_OFF', on ? 'working' : 'idle', '' + ctx.getNumber('count'))
    })

  // 3) A custom sensor: emits an analog signal proportional to the coal in a bound lane.
  event.create('kubejs:coal_gauge')
    .category('control')
    .phase('control')
    .itemLane('lane', 'A')
    .signalOut('out')
    .displayName('Coal Gauge')
    .faceTexture('fieldframe:block/module/resource_sensor')
    .onTick(ctx => {
      const coal = ctx.itemCountOf('lane', 'minecraft:coal')
      ctx.emit('out', Math.min(15, Math.floor(coal / 4)))
      ctx.setStatus('LEVEL', 'working', coal + ' coal')
    })
})

// A sixth chassis tier above Resonant, with its own upgrade kit item kubejs:mythic_upgrade_kit (craft it in
// server_scripts). Arguments: id, level, speed, extra bays, extra stacks per lane, extra mB, extra RF, extra RF/t.
FieldframeEvents.registerModules(event => {
  event.tier('kubejs:mythic', 5, 6.0, 3, 6, 16000, 1000000, 4096)
  // A service-bay upgrade with its own item: two more stacks in each storage and 4 more items a tick.
  event.upgrade('kubejs:overclock_card', {extraSlotsPerLane: 2, extraItemsPerTick: 4})
})
