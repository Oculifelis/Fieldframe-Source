// Fieldframe example pack - client scripts (KubeJS 6 / Forge 1.20.1).
// Adds a manual chapter purely from script, composing the built-in page types.

FieldframeEvents.manual(event => {
  event.category('kubejs:pack_guide', 'Pack Guide', 'kubejs:smasher', 5)

  event.entry('kubejs:pack_guide/smasher', {
    category: 'kubejs:pack_guide',
    title: 'The Smasher',
    icon: 'kubejs:smasher',
    pages: [
      { type: 'fieldframe:text', text: 'This pack adds the **Smasher**, a processing module defined entirely in KubeJS. It uses the same processing engine, statuses and lanes as the built-in [[fieldframe:modules/crusher|Crusher]].' },
      { type: 'fieldframe:module_spec', module: 'kubejs:smasher' },
      { type: 'fieldframe:recipes', family: 'kubejs:smashing' },
      { type: 'fieldframe:warning', text: 'Pack rule: the Press will not compact iron nuggets in this pack (status CANCELLED_BY_RULE: no_nugget_press).' },
      { type: 'fieldframe:face_map', faces: { top: 'kubejs:smasher', bottom: 'fieldframe:fuel_engine', front: 'fieldframe:inventory_access_panel' },
        text: 'A minimal smashing machine.' }
    ],
    related: ['kubejs:pack_guide/pulse_divider']
  })

  event.entry('kubejs:pack_guide/pulse_divider', {
    category: 'kubejs:pack_guide',
    title: 'Pulse Divider',
    icon: 'kubejs:pulse_divider',
    pages: [
      { type: 'fieldframe:text', text: 'Toggles its output every N rising edges on its input channel. Its count is saved with the world.' },
      { type: 'fieldframe:module_spec', module: 'kubejs:pulse_divider' }
    ]
  })
})
