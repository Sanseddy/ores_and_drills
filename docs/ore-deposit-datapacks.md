# Ore deposit datapacks (NeoForge 1.21.1)

Rules are ordinary NeoForge biome modifiers. Put each rule in
`data/<namespace>/neoforge/biome_modifier/<name>.json`.

```json
{
  "type": "ores_and_drills:ore_deposit_rule",
  "ores": ["minecraft:diamond_ore", "#c:ores/tin"],
  "biomes": ["minecraft:dripstone_caves", "#minecraft:is_mountain"],
  "dimensions": ["minecraft:overworld"],
  "min_y": -48,
  "max_y": 32,
  "replaceables": ["#minecraft:base_stone_overworld", "create:limestone"],
  "priority": 10,
  "sizes": {
    "tiny": {
      "forced": true,
      "count": 2,
      "placement": "prefer_cave_wall",
      "size_multiplier": 0.8,
      "richness_multiplier": 1.0
    },
    "small": {
      "frequency": 1.25,
      "placement": "underground"
    },
    "large": {
      "frequency": 0.05,
      "placement": "underground",
      "size_multiplier": 1.2
    }
  }
}
```

Selectors beginning with `#` are tags. Missing block IDs, empty ore tags and biomes that do not
exist simply match nothing. `frequency` is the expected number of independent candidates in one
tier cell and accepts values from `0` to `64`; for example `1.25` is one guaranteed candidate plus
a deterministic 25% second slot. Omit a size to disable it.

Set `forced` to `true` to disable the frequency roll for that size. `count` then specifies how many
guaranteed independent candidates are created in every qualifying cell of an allowed biome and
accepts values from `1` to `64`. `frequency` may be omitted in forced mode. Biome, dimension,
height, replacement-block and placement-mode checks are still authoritative: `forced` does not
place a deposit into a forbidden biome or into terrain that contains no allowed host blocks.

Placement modes:

- `prefer_cave_wall`: try a cave wall first, then ordinary underground placement;
- `cave_wall`: require a suitable cave wall, without fallback;
- `underground`: do not search cave walls;
- `default`: cave-wall priority for `tiny`/`small`, underground for the other sizes.

If several rules overlap for the same ore, biome and size, the largest `priority` wins. Equal
priorities are resolved by a stable hash of the complete rule, so datapack load order cannot move
deposits. Once an ore has at least one explicit rule, its automatic source-feature conversion is
disabled; this makes the listed biome/dimension allow-list authoritative.

The immutable candidate seed contains the world seed, dimension, exact biome ID, ore ID, size,
cell X/Z, frequency slot and a stable rule signature. Both chunk generation and
`/locate oredeposit <ore> <size>` call the same planner and frequency gate.

This also applies to automatically converted vanilla and mod ores. Their original `PlacedFeature`
count, rarity, noise, height and target predicates are scanned into stable rarity metadata, but they
do not independently roll candidate existence afterward. Every tier uses the mod's immutable cell
planner. Its seed contains the world seed, dimension, selected source biome, material/ore, size and
cell coordinates. This is the same automatic plan consumed by `/locate`.

Registered source features are deduplicated by their registry key. Direct features use a stable
structural signature; JVM object identity and datapack iteration order are never seed inputs.

Multiple data-driven slots (`frequency > 1` or `forced` + `count`) select deterministic source chunks
inside the tier cell, so large candidates do not all start in the old central anchor chunk.

For three-dimensional biomes (including underground cave biomes), a slot does not test only one arbitrary
XYZ point. Generation and `/locate` run the same bounded, seed-permuted traversal of quart columns across
the whole cell and select a center that is actually inside the allowed biome. This keeps `forced` rules
effective in cave biomes without loading remote chunks or adding biome-specific code.

## Optional KubeJS 1.21.1 integration

No KubeJS dependency is required. KubeJS can emit the same biome-modifier JSON into its virtual
datapack from `kubejs/server_scripts/ore_deposits.js`:

```js
ServerEvents.generateData('after_mods', event => {
  event.json('kubejs:neoforge/biome_modifier/custom_tin_deposits', {
    type: 'ores_and_drills:ore_deposit_rule',
    ores: ['#c:ores/tin'],
    biomes: ['#minecraft:is_overworld'],
    dimensions: ['minecraft:overworld'],
    min_y: -32,
    max_y: 96,
    replaceables: ['#c:stones'],
    priority: 50,
    sizes: {
      tiny: { forced: true, count: 2, placement: 'prefer_cave_wall' },
      medium: { frequency: 0.4, placement: 'underground' }
    }
  })
})
```

Reload datapacks after changing scripts. A changed rule intentionally changes its stable signature,
therefore new chunks use a new deterministic layout.

KubeJS-generated JSON is decoded from the same NeoForge biome-modifier registry as an ordinary
datapack file. It has no separate random source or locate implementation.
