# Deposit generation

[Русская версия](../ru/ore-deposits.md)

Ores & Drills removes ordinary ore veins from the world and places deposits instead. A deposit block
(`ore_deposit`) looks like ore but holds a reserve: how many units of ore can still be mined from it. A
pickaxe takes one unit per break, a drill one unit per cycle. Once the reserve runs out, the block turns
into an exhausted deposit (`exhausted_ore_deposit`) and behaves like the rock it was in.

| Size | Purpose | Whole-deposit reserve at Richness 1 |
|---|---|---:|
| TINY | early game: pickaxe | 1–4 |
| SMALL | early game: pickaxe | 8–16 |
| MEDIUM | industrial mining: drills | 180,000–360,000 |
| LARGE | industrial mining: drills | 400,000–2,000,000 |

## Where the ore comes from

The mod reads the ore generation already present in the world: vanilla, from other mods, and as changed
by datapacks. Each such ore becomes a source of deposits, and its own veins no longer generate, so ore
never appears twice.

- **Identical materials are unified.** If three mods add copper, one copper ore generates: the one
  whose mod ranks highest in `mod_priorities` (config or the "Ore Settings" screen).
- **`ignored_ores` in the config** takes an ore out of the mod's hands: its veins stay as they are and
  count neither for unification nor for rarity.
- **An explicit datapack rule** takes over an ore completely: automatic generation stops for it, and
  the ore appears only where the rule allows ([rule format](ore-deposit-datapacks.md)).

## Host rock

A deposit replaces only blocks in the `#c:stones` tag. While tags load, the mod adds the base rock of
every terrain generator (`default_block` of `noise_settings`) to it, including generators defined
inline in a dimension. If a datapack removes a block from the tag with a `remove` entry, deposits leave
it alone too.

Every deposit block takes its material's variant for the rock it replaced, as vanilla does: deepslate
ore in deepslate and tuff, regular ore in stone, granite, diorite and andesite, nether ore in
netherrack, end ore in end stone. The variant from the same mod is preferred, then from another mod. An
ore without the needed variant stays as it is.

Some mods create their own rock only after the base terrain, such as radrock in Alex's Caves' Toxic
Caves. For such rock the planner accepts ordinary stone as future radrock, but a deposit block is placed
only if the world really contains rock the rule allows.

## Small deposits: TINY and SMALL

### How many

The mod replays the source ore's placement: the same `count`, `rarity_filter`, `height_range`,
`in_square` and modded modifiers, with the same biome filter. Every spot where vanilla ore would have
tried to generate is an attempt. The mod keeps a small share of the attempts:

```text
λ = 0.0109375 × Frequency                           expected deposits per attempt
N = floor(λ) + (1 with probability frac(λ), else 0)  deposits from this attempt
type: TINY with probability 0.40, otherwise SMALL
```

With default settings an attempt yields TINY with probability 0.4375%, SMALL with 0.65625%, and nothing
in 98.90625% of cases. Coal, iron and copper therefore stay the most common, just as in vanilla.

- **Frequency** (0.1–6) scales λ with no 100% cap. When λ > 1, the extra deposits keep the attempt's
  height and get new X/Z inside the same chunk.
- **Size** scales the vein, **Richness** only the reserve.
- The result depends only on the world seed, the dimension, the source feature and the coordinates, not
  on the order in which chunks generate.

### Shape

Minecraft builds the vein itself: the mod calls vanilla `Feature.ORE` with the source ore's parameters
and takes the positions it would have filled. SMALL is a vein of the original size, TINY one half of it
but at least 4, since veins of 1–3 blocks are almost always empty. Two deposits never touch.

### Where they lie

`ore_deposits.small_deposit_placement` in the config controls this.

**`CAVE_WALL`** (default) makes small deposits easy to spot. Each deposit rolls a chance to lie on a cave
wall: 90% for the dimension's most common ore, 20% for an ore 100 or more times rarer, and on a
logarithmic scale in between. The mod estimates rarity once per dimension from 256 chunks with their
real biomes.

- Roll succeeds: the mod looks for the nearest cave wall (stone with air beside it) within 16 blocks
  horizontally and 12 vertically and lays the vein so it surfaces on the wall.
- No wall nearby, or the roll failed: the vein appears at the original spot, as in vanilla. Without rock
  there, the deposit does not appear.

**`INHERITED`** always uses the original spot, as vanilla ore does.

### Reserve

The whole-deposit reserve is split evenly between its blocks. If there are more blocks than units, the
extra blocks are dropped: blocks exposed to air stay first, then those closest to the centre. That way
trimming never hides a deposit from a cave.

## "Vanilla-like" mode

The "Vanilla-like" checkbox in "Ore Settings" is saved with the world. In this mode small deposits
appear exactly where ordinary ore would, in the same amount:

- every vanilla vein becomes a deposit, and each of its blocks holds one unit of ore;
- a vein of 1–2 blocks counts as TINY, larger ones as SMALL;
- Richness multiplies the ore per block; Frequency below 1 drops some veins, above 1 adds ore to the
  blocks;
- MEDIUM and LARGE generate as usual;
- `/locate` does not search TINY and SMALL, except for ores with explicit datapack rules.

## Large deposits: MEDIUM and LARGE

### How many and where

MEDIUM and LARGE are laid out on their own grids, independently of small deposits. The distance between
large deposits is set by `minimum_medium_large_spacing` and `maximum_medium_large_spacing` in the
config: 200–600 blocks by default, never below 50. A weighted lottery over the source ores' frequency
decides which material gets a spot, and rare ores get a bonus to their chance.

The layout depends only on the world seed, the dimension, the material and the cell coordinates. The
order in which the player explores has no effect on it.

### Shape

| | MEDIUM | LARGE |
|---|---:|---:|
| Width across | 20–30 | 40–60 |
| Layer thickness | 3–5 | 5–7 |
| Chunks covered | about 4 | 11–16 |

A deposit is an irregular wavy layer, like a resource patch in Factorio. With default settings ore fills
the whole layer wherever the rock is suitable. The formulas are in the
[appendix](#formulas-for-medium-and-large).

### Reserve

Reserve and size are chosen independently, so Richness never changes the shape. The reserve is spread
unevenly: there is more ore near the centre and the middle of the layer. No single block holds more
than 5,000 units, and the sum over all blocks equals the deposit's reserve exactly.

### How a deposit appears in the world

A deposit covers several chunks but has one plan. Each chunk places only its own part as it generates
and never touches its neighbours.

- Blocks that land on fluid, another ore, a structure or unsuitable rock are skipped. Their reserve goes
  to the remaining blocks of the same chunk.
- If less than 60% of the layer has suitable rock, or the deposit ends up below its size's minimum, it
  does not appear at all.
- Large deposits never overlap: at least one block of rock always separates them.
- Vanilla gravel does not generate inside a large deposit; gravel that got there first is replaced with
  ore.
- Decorating a chunk again does not refill an already mined reserve.
- Why a block or a deposit did not appear is written to `debug.log`.

## Forced rules

A datapack rule with `forced: true` places an exact number of deposits in every biome instance: in
every cave or every patch of the biome ([details](ore-deposit-datapacks.md#forced)).
`DataDrivenDepositLedger` keeps count. Unlike the rest of generation, the spot of such a deposit depends
on which chunk generated first.

If a forced deposit reaches into chunks that are already fully generated, the mod places those parts
later, when the chunks load.

## Locating: `/locate oredeposit`

```text
/locate oredeposit <ore> <tiny|small|medium|large>
```

An operator command. The ore is suggested from the `#c:ores` tag. The search covers 20,000 blocks and
never loads distant chunks.

- **MEDIUM, LARGE and deposits from datapack rules** are found through their grids and the list of
  deposits already placed.
- **Automatic TINY and SMALL** are too numerous to remember. In loaded chunks the command finds a real
  deposit block and reports it as confirmed. For chunks not generated yet it replays the generation and
  reports "predicted from the seed, placement not yet verified". The prediction points at the original
  spot, not at the cave wall the deposit may move to, and knows nothing about caves or structures.
- Chunks that are generated but unloaded are neither read nor predicted.

## Storage

Each block's reserve is stored in the chunk's data. The list of placed large deposits, the forced-rule
ledger and the ore palette are stored with the world. The storage format is `Format=1`; worlds with an
earlier format do not load.

Generation changes affect only new chunks.

## Known limitations

- The small-deposit balance (1.09375%, 40 to 60) has not been playtested yet.
- A source without a `biome` modifier runs only in its own biome, while a vanilla feature without that
  filter could spill into neighbouring biomes of the chunk.
- `random_selector` branches are rolled independently, so their mutual exclusivity holds only on
  average.
- When λ > 1 for a source without `in_square`, the extra deposits get the same spot and are almost
  always rejected as overlapping.
- Caves and structures that other mods add later can cut through a large deposit; parts already placed
  in neighbouring chunks stay.

## Formulas for MEDIUM and LARGE

The shape follows Factorio's model of a single resource patch
([resource-autoplace.lua](https://github.com/wube/factorio-data/blob/master/core/lualib/resource-autoplace.lua)):
a cone with noise octaves and an amplitude of 1/8. Regional `spot_noise` selection, the starting area
and growth with distance are not ported; the engine's `basis_noise` is replaced by gradient noise `G`.

Outline from above:

```text
QF = clamp(lerp(QFmin, QFmax, hash(seed, 211)) * Size, QFmin, QFmax)
R  = clamp(0.1 * cbrt(QF), Rmin, Rmax)
MEDIUM: QFmin = 1,000,000, QFmax = 3,375,000
LARGE:  QFmin = 8,000,000, QFmax = 27,000,000

B(u,v)    = G(u/8, v/8) + G(u/24, v/24) + 1.5*G(u/64, v/64) - 1/3
Fraw(u,v) = R * (1 - length((u,v) - jitter)/R + B(u,v)/8)
Fxz(x,z)  = scale * Fraw(x/scale + sourceCenterX, z/scale + sourceCenterZ)
```

The main edge-connected region of the positive field is kept. The outline is then scaled uniformly so
that its width `max(maxX - minX + 1, maxZ - minZ + 1)` falls into the size's range. It is never clipped
to a circle or a square.

The layer vertically:

```text
Ywave   = A1*N1(x/32, z/32) + A2*N2(x/12, z/12)     (A1, A2) = (2.5, 0.65) MEDIUM; (3.5, 0.85) LARGE
T       = clamp(T0 + 0.65*Nt(x/12, z/12), Tmin, Tmax)
top0    = Ywave + T/2 + 0.30*Ntop1(x/10, z/10) + 0.12*Ntop2(x/4, z/4)
bottom0 = Ywave - T/2 + 0.30*Nbot1(x/10, z/10) + 0.12*Nbot2(x/4, z/4)
actualT = clamp(top0 - bottom0, Tmin, Tmax)
center  = (top0 + bottom0)/2
```

The middle of the layer may shift between neighbouring columns by at most 0.85 blocks for MEDIUM and
1.0 for LARGE.

Block weight used to split the reserve:

```text
H = clamp(Fxz / (0.65*R), 0, 1)
V = clamp(1 - 2*|y - center| / actualT, 0, 1)
W = clamp(0.55*H + 0.35*V + 0.25*(0.65*N3a(x/8, y/8, z/8) + 0.35*N3b(x/3, y/3, z/3)), 0, 1)
```
