# Ores & Drills

**English** · [Русский](README.ru.md)

A Factorio-inspired mod for Minecraft 1.21.1 on NeoForge. Ore no longer comes in veins but in deposits
with a reserve: small ones are dug out with a pickaxe early on, while large deposits holding hundreds of
thousands of units are worked with drills.

## Features

- **Deposits in four sizes.** TINY and SMALL replace ordinary veins and supply the early game. MEDIUM
  and LARGE are layers 20–60 blocks across holding 180,000–2,000,000 units; drills mine them. Every
  deposit block stores its ore and remaining reserve. A pickaxe takes one unit per break, and the more
  ore a block holds, the longer it takes to break.
- **Four drills.** Drills mine the ore beneath and around them, keeping deposit blocks in place while
  they still hold ore.
- **Per-world generation settings.** The "Ore Settings" button on the world creation screen opens a
  table of every ore: on/off, Frequency, Size, Richness, per-dimension exclusions, mod priority and the
  "Vanilla-like" mode.
- **Ore unification.** The same material from several mods (copper from three mods) generates as one
  ore, chosen by a priority list.
- **Deposit map.** MEDIUM and LARGE deposits the player has seen or stood on are charted on JourneyMap,
  Xaero's World Map, Xaero's Minimap or FTB Chunks, with name, size and remaining amount.
- **Locating.** `/locate oredeposit <ore> <tiny|small|medium|large>` finds the nearest deposit without
  loading distant chunks.
- **Datapacks.** Custom deposit rules, settings presets and mining recipes with fluids, all in JSON.

## Drills

| Drill | Body | Mining area | Speed, ore/s | Mines like | Power | Productivity |
|---|---|---|---:|---|---|---:|
| Burner | 2×2 | 2×2 | 0.125 | stone pickaxe | solid fuel | — |
| Electric | 3×3 | 5×5 | 0.325 | iron pickaxe | 80 FE/t, 32,000 FE buffer | — |
| Advanced | 5×5 | 13×13 | 0.75 | diamond pickaxe | 320 FE/t, 64,000 FE buffer | +20% |
| Ultimate | 7×7 | 19×19 | 2 | netherite pickaxe | 800 FE/t, 128,000 FE buffer | +70% |

A drill scans its area 9 blocks down and works it as a funnel spreading out from the centre. Ore its
pickaxe could not harvest is skipped. Productivity works as in Factorio: the bar fills with every cycle
and, once full, yields a free copy of the cycle's output without touching the deposit's reserve.

The advanced and ultimate drills have two tanks each (10,000 and 20,000 mB) and side fluid ports. A
fluid is needed when a datapack gives an ore a recipe that consumes one, such as uranium with sulfuric
acid ([drill mining recipes](docs/en/drill-mining-recipes.md)).

A drill pushes its output into an adjacent inventory or drops it in front of its main block.

## Compatibility

Only [GeckoLib](https://modrinth.com/mod/geckolib) is required. Everything else activates when the mod
is installed:

- **JEI, EMI**: drill mining recipe pages with the drills that can mine each ore. **EMI Ores**: deposit
  generation instead of the replaced veins.
- **Jade**: the ore and remaining amount of a deposit block, fluid requirements.
- **JourneyMap, Xaero's World Map, Xaero's Minimap, FTB Chunks**: a deposit layer on the map.
- **Create, Mekanism (Digital Miner), Just Hammers**: machines and hammers take one unit at a time
  instead of erasing the whole block.
- **Sable, Simulated**: drills move into physics structures as a whole; a deposit inside such a
  structure loses ore when it hits things.
- **Alex's Caves**: deposits in rock the mod creates after the base terrain (radrock).

## Configuration

- **World creation screen → "Ore Settings".** Saved in that world's `level.dat`, never carried over to
  other worlds.
- **Mod config** ("Config" button in the mod list, or `config/ores_and_drills-common.toml`): spacing
  between MEDIUM/LARGE deposits, ores the mod leaves alone (`ignored_ores`), mod priority, active preset,
  cave-wall placement of small deposits.
- **Datapacks:** [deposit rules and presets](docs/en/ore-deposit-datapacks.md),
  [drill mining recipes](docs/en/drill-mining-recipes.md). Ready-made examples are in
  [examples/datapacks/](examples/datapacks/).

Generation changes apply only to new chunks; terrain that is already generated is not rebuilt.

## Documentation

- [Deposit generation](docs/en/ore-deposits.md): how deposits of every size appear, the "Vanilla-like"
  mode, `/locate`.
- [Datapacks: deposits and presets](docs/en/ore-deposit-datapacks.md): rule and preset format.
- [Drill mining recipes](docs/en/drill-mining-recipes.md): fluids, conditions, recipe selection.
- [Development](docs/en/development.md): building, tests, code rules.

## Development

Requires Java 21.

```powershell
.\gradlew.bat build       # the mod: build/libs/ores_and_drills-<version>.jar
.\gradlew.bat test        # unit tests
.\gradlew.bat runClient   # development client
```

```text
src/
├── main/java/dev/
│   ├── OresAndDrillsMod.java   entry point
│   ├── client/                 rendering, screens, deposit map
│   ├── command/                /locate oredeposit
│   ├── compat/                 integrations with other mods
│   ├── config/                 config, world settings, presets
│   ├── data/                   data generation
│   ├── drill/                  the four drills
│   ├── mixin/                  mixins into Minecraft and other mods
│   ├── network/                network payloads
│   ├── recipe/                 drill mining recipes
│   ├── registry/               block, item and other registrations
│   ├── util/                   shared math
│   └── world/                  blocks, menus, generation, storage
├── main/resources/             mod assets and data
├── main/templates/             neoforge.mods.toml template
├── test/java/                  unit tests, mirroring the main packages
└── gameTest/                   GameTests, enabled with -PcompactGameTests
```

Where new code goes:

- Game content goes into `world`: blocks in `world.block`, block entities in `world.block.entity`,
  items in `world.item`, menus in `world.inventory`. Code for a single drill lives next to it in `drill`.
- Registration happens only in the `registry/` classes, never inside game classes.
- Client-only code stays in `client/` so it never reaches a dedicated server.
- Models, blockstates, translations and textures go into `assets/ores_and_drills/`; recipes, loot
  tables, tags and biome modifiers into `data/<namespace>/`.
- Isolated math and config parsing get a unit test in the same package under `src/test/java`.

Tests, code style and generation rules are covered in [docs/en/development.md](docs/en/development.md).
