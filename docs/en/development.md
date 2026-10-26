# Development

[Русская версия](../ru/development.md)

## Building

Requires Java 21.

```powershell
.\gradlew.bat build       # the mod: build/libs/ores_and_drills-<version>.jar
.\gradlew.bat test        # unit tests from src/test
.\gradlew.bat runClient   # development client in the run folder
.\gradlew.bat runData     # data generation into src/generated/resources
```

`runData` runs in its own folder, `build/recipe-datagen`. The generated JSON in
`src/generated/resources` is committed; the generator's cache (`.cache`) is not.

## GameTests

Integration tests live in `src/gameTest`. They are enabled only by a flag and never end up in the
released jar, and neither do their test recipes and rules from `src/gameTest/resources`.

```powershell
.\gradlew.bat build runGameTestServer -PcompactGameTests --console=plain
```

The tests run on a server in a separate world, `build/compact-gametest`. Some reports are written there
too (`small-deposit-*.txt`) and to `build/reports/factorio-deposits/`.

The `-PalexCavesGameTests` flag additionally loads Alex's Caves and Citadel from `run/mods`. Without it
the late-rock test `LateCaveRockGameTests` is skipped.

| Class | What it checks |
|---|---|
| `SmallDepositAttemptGameTests` | TINY/SMALL attempt stream, shares, reserves, `/locate` |
| `SmallDepositFrequencyGameTests` | TINY/SMALL from `frequency` rules and exact forced counts |
| `VanillaOreVeinGameTests` | a deposit vein matches vanilla `Feature.ORE` and writes nothing to the world while collected |
| `MediumLargeDigestGameTests` | fingerprint of MEDIUM/LARGE predictions: catches accidental changes to their generation |
| `CompactDepositGameTests` | MEDIUM/LARGE across chunks, exact reserve sum, forced continuations, gravel |
| `CompactDepositTerrainGameTests` | batched rock calculation matches `getBaseColumn` |
| `CompactDepositConcurrencyGameTests` | no deadlocks while writing blocks |
| `OreDepositStorageGameTests` | saving and loading chunk and world data |
| `LateCaveRockGameTests` | rock that mods create after the terrain (Alex's Caves radrock) |
| `LocateCellGameTests` | `/locate` candidates belong to their own cell |
| `OreSpawnDimensionsGameTests`, `OreTagsGameTests` | recognising ores by features and item tags |
| `OreDepositImpactGameTests` | deposits of every size lose ore on impacts |
| `DrillMiningRecipeGameTests` | mining recipes, fluids, ports, manual mining |

## Code style

The format is [google-java-format](https://github.com/google/google-java-format) in AOSP style: 4-space
indent, lines up to 100 characters. Spotless maintains it:

```powershell
.\gradlew.bat spotlessApply   # format
.\gradlew.bat spotlessCheck   # check only
```

`build` does not check formatting, so an unformatted file never blocks a test run. The formatter wraps
long `//` comments but never joins them back into a paragraph: reread changed comments after running it.

Comments are written in English and explain why the code is the way it is: engine constraints,
compatibility with other mods, invariants. They should not retell the code or describe its history;
that is what git is for.

## Where things are

Deposit generation lives in `dev.world.level.levelgen`:

| Classes | Responsibility |
|---|---|
| `DepositLayouts`, `TierDepositResolver` and its implementations | per-size grids and seed spaces |
| `SmallDepositAttemptPlanner`, `SmallDepositAttemptMath`, `SmallDepositDiagnostics` | TINY/SMALL attempts and their counters in `debug.log` |
| `VanillaOreVein` | collecting a vein through vanilla `Feature.ORE` |
| `CaveWallDetector`, `CaveWallAnchor`, `WallDepositGeometry` | cave walls and wall-bound shapes |
| `CompactDepositGeometry`, `CompactDepositTerrain` | MEDIUM/LARGE shape and batched rock calculation |
| `OreDepositFeature` | placing deposits of every size |
| `DataDrivenOreDepositBiomeModifier`, `DataDrivenDepositLedger` | datapack rules and forced counts |
| `DepositLocator`, `OreDepositLocateCommand` | `/locate oredeposit` |
| `ConfirmedDepositIndex`, `OreDepositChunkData` | storage |
| `DepositWorldgenGuard` | gravel protection for MEDIUM/LARGE |

## Generation rules

Each of these rules came from a real hang or data loss. Breaking one usually shows up in the game, not
in the tests.

- **A generation thread writes only to its own chunk.** A neighbouring chunk may not have terrain yet,
  and its status must not be raised just to check something. Deposit parts in neighbours are placed
  when those chunks generate.
- **The `ConfirmedDepositIndex` monitor is never held while writing to the world.** `Level.setBlock`
  calls other mods' handlers (Sable physics, for example), which may wait for a neighbouring chunk to
  generate. The generation thread meanwhile waits for the same monitor: a deadlock. The monitor guards
  only the index's collections; block writes, handlers and sync run after it is released. Re-entering
  placement for a chunk already in progress is skipped.
- **Gravel protection only reads finished data.** `DepositWorldgenGuard` and `OreFeatureMixin` do not
  build plans, search for candidates or evaluate noise. Building a plan from there once took about
  128 seconds of CPU time on the first chunk.
- **Rock is calculated in batches.** `CompactDepositTerrain` uses one `NoiseChunk` per chunk and limits
  the calculation to the deposit's height. Calling `getBaseColumn` for every LARGE column is 125 times
  slower and allocates gigabytes. A generator's own `getBaseColumn` is used only for third-party
  generators and non-standard cell sizes.
- **Generation is deterministic.** Automatic plans depend only on the seed and coordinates, not on
  generation order, datapack load order or JVM object identity. The one exception is how forced
  deposits pick their centers.
- **Caches are bounded:** per world, 24 geometries, 96 plans, 2048 candidates and 64 gravel-protection
  masks. Caches keyed by world hold it through a weak reference.

## Storage format

`OreDepositChunkData`, `ConfirmedDepositIndex` and unfinished forced plans write `Format=1` and do not
read other versions. When the format changes incompatibly, bump the number and check world loading with
`OreDepositStorageGameTests`.

All of the mod's SavedData extend `FastSavedData`. The NBT is built fresh on every save and never
changed afterwards, so NeoForge's deep copy is skipped and compression (`BEST_SPEED`) runs on the I/O
thread.

## Extending

- A drill recipe condition: `DrillMiningCondition.register(id, mapCodec)` during mod initialization on
  both server and client, before recipes load ([drill mining recipes](drill-mining-recipes.md)).
- A new drill with its own recipe tier: the canonical `MiningDrillTier` constructor.
- Deposit rules and presets need only JSON ([datapacks](ore-deposit-datapacks.md)).
