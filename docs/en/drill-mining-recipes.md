# Drill mining recipes

[Русская версия](../ru/drill-mining-recipes.md)

A drill mining recipe tells a drill how to work a particular ore: whether it needs a fluid, which drill
qualifies, under what conditions, and what comes out. The mod ships one default recipe: every ore in
`#c:ores` is mined without fluid and yields its normal drop. Everything else comes from datapacks.

Recipes live in `data/<namespace>/recipe/<path>.json` (the folder is `recipe`, singular) and reload with
`/reload`; the world does not need to be recreated. Clients receive them from the server; JEI and EMI
show them in the "Drill mining" category.

## Example

```json
{
  "type": "ores_and_drills:drill_mining",
  "input": "#c:ores/uranium",
  "fluid": {"tag": "c:sulfuric_acid", "amount": 100},
  "minimum_drill_tier": 3,
  "priority": 10,
  "conditions": [
    {"type": "ores_and_drills:dimension", "dimension": "minecraft:overworld"},
    {"type": "ores_and_drills:height", "min": -64, "max": 16}
  ]
}
```

## Fields

| Field | Meaning | Default |
|---|---|---|
| `input` | which blocks the recipe mines | required |
| `fluid` | fluid consumed per cycle | no fluid |
| `minimum_drill_tier` | the weakest drill that qualifies, from 1 | 1 |
| `conditions` | conditions checked while mining | none |
| `priority` | priority among recipes for the same block | 0 |
| `result` | what comes out instead of the normal drop | the ore's normal drop |
| `neoforge:conditions` | whether the recipe loads at all | always |

### `input`

A block tag (`"#c:ores/gold"`) or a list of IDs (`["minecraft:gold_ore", "minecraft:deepslate_gold_ore"]`).
Even a single block is written as a list. The blocks do not have to be in an ore tag.

### `fluid`

A fluid and its amount in mB (1000 mB is one bucket). The amount must be positive and fit in the
drill's tank, otherwise the drill waits for fluid forever. Accepted forms:

- a specific fluid: `{"id": "minecraft:water", "amount": 100}`;
- a tag: `{"tag": "c:sulfuric_acid", "amount": 100}`, without `#`;
- a standard NeoForge ingredient: `{"fluid": "minecraft:water", "amount": 100}`.

`id` and `tag` cannot be combined. `id` takes a fluid ID such as
`modern_industrialization:sulfuric_acid`, `tag` takes a tag name such as `c:sulfuric_acid`.

### `minimum_drill_tier`

| Tier | Drill | Tanks |
|---:|---|---|
| 1 | Burner Mining Drill | none |
| 2 | Electric Mining Drill | none |
| 3 | Advanced Mining Drill | 2 × 10,000 mB |
| 4 | Ultimate Mining Drill | 2 × 20,000 mB |

Only the advanced and ultimate drills have tanks, so a recipe with `fluid` requires tier 3 or higher:
values 1 and 2 count as 3.

Besides the recipe tier, a drill checks whether its pickaxe could harvest the ore: the burner drill
mines like a stone pickaxe, the electric like iron, the advanced like diamond, the ultimate like
netherite. So a burner drill will not take diamond ore even with a tier 1 recipe.

JEI and EMI show the effective requirement as drill slots in the lower-left corner of the page: only
drills that will actually mine the ore are listed. The "Minimum drill tier" line appears only when no
drill from the mod qualifies.

### `conditions`

All conditions must hold at the same time.

| Condition | Example |
|---|---|
| dimension | `{"type": "ores_and_drills:dimension", "dimension": "minecraft:overworld"}` |
| ore height, inclusive | `{"type": "ores_and_drills:height", "min": -64, "max": 16}` |
| biome tag at the ore | `{"type": "ores_and_drills:biome", "tag": "c:is_overworld"}` |
| redstone signal at the drill's main block | `{"type": "ores_and_drills:redstone", "powered": true}` |

`height` accepts just `min` or just `max`. `redstone` defaults to `powered: true`.

### `result`

An item, for example `{"id": "minecraft:raw_gold", "count": 2}`. Without this field the drill gets the
ore's normal drop. One result corresponds to one unit of reserve; the drill's productivity bonus comes
on top.

### `neoforge:conditions`

Standard NeoForge load conditions, for example loading the recipe only when a certain mod is installed.
They are checked once when recipes load, while `conditions` are checked on every mining cycle.

## Which recipe is used

Only one recipe applies to each block. It is chosen by:

1. higher `priority`;
2. a recipe with fluid before one without;
3. a block list before a tag;
4. recipe ID in alphabetical order.

If the chosen recipe's conditions do not hold, the drill skips the block and spends nothing. It does not
fall back to a simpler recipe. Two recipes with different fluids for the same block are not
interchangeable: `priority` decides which one applies.

## How a drill uses fluid

- The drill checks both tanks and uses the one holding enough suitable fluid for a full cycle. Different
  fluids from one tag do not add up: 50 mB of one and 50 mB of another do not make 100 mB.
- Fluid is consumed once per cycle for each recipe that ran. If the cycle fails (output full, wrong
  fluid, mining failed), no fluid is used.
- While an ore waits for fluid, the drill mines other available ore in its area. With nothing to mine,
  progress and energy use stop; fuel already burning in a burner drill burns out as in a furnace.
- An empty tag does not make mining free: the drill waits for fluid.

Fluid enters through the two side ports of the advanced and ultimate drills. The ports work both ways
and share the drill's tanks; the drill also pulls fluid from an adjacent storage on its own. Buckets can
fill and empty it too. Tank contents are saved with the drill.

## Ore that needs fluid

If an ore has at least one recipe with fluid, only drills from this mod can get anything out of it.
This holds even when the recipe's conditions are not currently met.

- Pickaxes, hammers, other mods' machines and explosions break such ore with no drop and no XP. For a
  deposit, each such break simply burns one unit of reserve.
- Jade shows "✕ Cannot mine by hand" and "Requires a drill with fluid" on such ore.

The lock follows loaded recipes, not files on disk. If the log contains
`Parsing error loading recipe ... Unknown registry key ... minecraft:fluid`, the recipe did not load
and has no effect. A common cause is a tag in the `id` field: `{"id": "c:sulfuric_acid"}` instead of
`{"tag": "c:sulfuric_acid"}`.

## Fluid tags

A custom fluid tag is a file `data/<namespace>/tags/fluid/<name>.json` with a `values` array. Tag
contents refresh on `/reload` together with recipes. JEI shows every fluid of the tag, except
duplicates with the same name.

## Examples

Datapack [examples/datapacks/drill-mining](../../examples/datapacks/drill-mining): copy the folder into
`<world>/datapacks/` and run `/reload`.

- `wet_gold.json` is minimal: gold needs 100 mB of water and an advanced drill, in the Overworld only.
- `all_options.json` uses every field. Redstone ore is mined only by the ultimate drill, with 250 mB of
  fluid from the `minecraft:water` tag, in the Overworld, at heights −64 to 16, in `c:is_overworld`
  biomes and only with a redstone signal. Each unit of reserve yields 8 redstone dust. `priority: 20`
  puts the recipe above others for these blocks, and `neoforge:conditions` loads it only when Ores &
  Drills is installed.

With this datapack, gold and redstone ore stop dropping anything when mined by hand in every dimension:
they now have recipes with fluid.

## For developers

- New fluids and blocks need only JSON.
- A custom condition type is registered with `DrillMiningCondition.register(id, mapCodec)` during mod
  initialization on both server and client, before recipes load.
- A new drill can get its own recipe tier through the canonical `MiningDrillTier` constructor.
- The default recipe is generated by `DrillMiningRecipeProvider` (`gradlew runData`).
- Main classes: `DrillMiningRecipe`, `DrillMiningRecipes`, `DrillMiningFluid`, `DrillMiningCondition`,
  `FluidLockedOreDrops`, `DrillMiningJeiPlugin`, `DrillMiningEmiPlugin`. How NeoForge recipes work is
  covered in the [NeoForge 1.21.1 documentation](https://docs.neoforged.net/docs/1.21.1/resources/server/recipes/).
