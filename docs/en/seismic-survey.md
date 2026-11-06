# Seismic survey

[Русская версия](../ru/seismic-survey.md)

Large deposits are rare and buried. The seismic survey finds them the way geophysicists do: a station
strikes the ground, sensors record how the subsurface reflects the waves, and the player interprets the
resulting anomaly maps. It is not an X-ray. A map shows how strongly the ground reflects, not where ore
lies; caves, dense intrusions and rock contacts reflect too. Only a borehole confirms ore.

## The loop

1. **Place a seismic survey station** (3×3×3, one item) on firm ground and power it with FE.
2. **Regional survey** (2048 blocks, about 60 s): a coarse intensity map of the whole region. Large
   deposits stand out; medium ones barely do.
3. **Pick an anomaly**, move closer, **set out geophones** around it in different directions (3–6).
4. **Detailed survey** (512 blocks, about 30 s): a finer survey with polarity, interpreted depth and
   absorption. Every anomaly becomes a **target** with an estimated position, size and the
   probabilities of what causes it. The station builds its **3D map** — a LiDAR-style point cloud —
   while the pulse travels outwards; with a **cave analyser** it maps the caves as well.
5. **Drill** at the most promising point with the **exploration drill**. Its analyser identifies the
   ore of the core, gives its depth and the ore around the hole.
6. **Mine** the confirmed deposit with the existing drills.

Each step narrows the uncertainty: more geophones, other station positions (stacked into one map),
the detailed programme and boreholes (which calibrate the velocity) all sharpen the picture; the 3D map
averages them. Repeating a survey from the same position with the same equipment reproduces the same
result and does not help.

## Equipment

| Block | Size | Role |
|---|---|---|
| Seismic survey station | 3×3×3 | source and recorder; stores up to 8 surveys (configurable) |
| Geophone | small | receiver; registers itself, works even in unloaded chunks |
| Exploration drill | 1 block | cores the column under itself; produces a core sample |
| Scanner upgrade module | item | raises the scanner level of a station or exploration drill by one (0–4) |
| Cave analyser module | item | installs or improves the cave analyser of a station or exploration drill (0–3) |

**Station.** The station takes FE on every face of every block (buffer 120,000 FE; 80 FE/t regional,
60 FE/t detailed). It needs nine sturdy blocks below; soft ground (soil, sand) couples less energy into
the rock and costs signal. The model shows idle, start, repeating impacts, data processing, finish and a
no-power state; lamps and the recorder screen change with it. Breaking any of its 27 blocks breaks the
station and drops one item that keeps the station's survey archive and its scanner level, so a moved
station can stack surveys from several positions. Upgrade modules are used on any block of the station
(see [Scanner levels](#scanner-levels)); installed upgrades show on the model.

**Geophones** need a sturdy block below. The station uses the nearest 16 registered geophones inside the
survey radius. Geophones in loaded chunks are checked against the world; ones in unloaded chunks are
trusted, since only their own removal unregisters them.

**Exploration drill.** Powered by FE (40 FE/t, 4 ticks per block, up to 256 blocks deep, stops at
bedrock). It logs the column without removing blocks: rock types, voids, fluids and ore, plus ore the
logging tool senses within 3 blocks of the hole. The core sample reports the ore interval, the
analyser's identification of the ore with probabilities, whether the body is an industrial
(MEDIUM/LARGE) deposit, and the ore units within 4 blocks of the hole only — never the whole deposit.
The drill accepts the same upgrade modules as the station; its levels are kept by the dropped item and
show on the model. Boreholes appear in station maps. The drill's **3D log** (the "3D" button) shows the
core as it is drilled, ore the logging tool senses beside the hole and, with a cave analyser, the walls
of voids in a ring around the hole (4/8/12 blocks, read from loaded chunks only); it is the drill's own
map and is replaced by its next borehole.

**On Sable bodies.** A station or drill built on a Sable physics structure (a ship, vehicle or
platform) works from the ground under it: from the first non-air block below the structure's position
in the world (water counts, so a floating station surveys like a seismic vessel). That point is fixed
when a survey or borehole starts and is saved with it, so the result, the map and the borehole lie in
world coordinates and match a station on the ground. The station's impacts need it to stay put: drifting
more than 3 blocks sideways before all impacts are made interrupts the survey ("the station moved");
once processing has started the structure may move freely. A drill pulled more than 1.5 blocks off its
hole ends the borehole and hands out the core drilled so far. Only loaded ground is read; a drill waits
while the column under it is unloaded. Geophones must stand on the ground, not on the structure.
Simulated's Physics Assembler (`simulated:physics_assembler`) takes the station as a whole once it
reaches any of its 27 blocks, glued or not; assembling, disassembling and turning the structure keep
the station intact with its archive and upgrades.

## Station screen and the 3D map

The console looks like a vanilla container and lays itself out from the window size and the measured
width of every label, so it holds together at any GUI scale and in any language. The left side is the
**3D view**, the right side four tabs:

- **Survey.** Installed levels; one **detail slider** with six settings from close and finest to full
  regional — each a programme and a radius within what the scanner allows — with its radius, map cell,
  expected position error, whether depth is resolved, the cave map, duration and energy; start and
  stop; overall progress, how far the pulse and the void analysis have travelled; energy; what the view
  shows (deposits, caves or both); camera reset and centring; point cloud quality.
- **Object.** The selected structure: name, type, coordinates (marked approximate with their errors),
  depth below the station, size, confidence, the possible sources with probabilities, how many surveys
  measured it and how long ago. **Copy coordinates** puts `X: …, Y: …, Z: …` on the clipboard; **Place
  waypoint** adds a waypoint (see below); Previous/Next step through the structures.
- **Log.** Reports of the saved surveys: programme, radius, position, level, geophones, coverage,
  quality, accuracy, targets.
- **Equipment.** What each module level does and what the next one adds; the dimension's ore table
  (class, prevalence, generation heights, chance to name each ore now and at the best level).

### The 3D map

Every spatial result is shown only here, as a point cloud on a dark background. It is not a model of
the true bodies: each point is a processed measurement scattered over its own uncertainty, so a vague
reading is a thin haze and a sharp one a tight cluster.

- **Seismic points** are the survey's own record: every map cell and depth bin whose residual stands at
  least 3σ out of the noise. Blue points reflect less than their surroundings (voids, porous rock), amber
  ones more; points that belong to an interpreted target take the colour of its most probable source.
  A target owns the points inside its estimated zone (for a resolved lens, its halo's outline) and those
  connected to them above 3σ within twice that zone, so an anomaly's fringe is not left amber. Where the survey did not resolve depth, a cell is a column of points through the whole depth.
- **Targets** also fill their estimated body (outline, thickness, orientation) blurred by their errors.
- **Caves** (cave analyser) are points on their walls, coloured by what fills them (air, water, lava).
  Confirmed connections between caves are solid white lines, suspected ones dashed violet.
- Station, geophones, boreholes and the outlines of the surveyed volumes are drawn as well; outside the
  surveyed volumes the view stays dark.

**Building in real time.** The survey is computed in the background while the station strikes. Points
are revealed as the seismic front — and, separately, the analyser's front — travels outwards: a 32-block
part of the map appears when the front passes its centre, new points fade in with a short flash and
points near the front glow as it sweeps past. A structure becomes selectable as soon as the front reaches
it or its first points. If the computation is slower than the pulses, the map still spreads out over at
least three seconds instead of appearing at once.

**Refining.** A new survey refines the map instead of adding points: a cell measured again is averaged
with earlier readings (by inverse variance, older readings counting half after five days), a covered
cell the new survey no longer sees fades and finally disappears, and a target seen again moves to the
weighted position, its error shrinks and its identifications are fused (p ∝ p₁·p₂/prior). Only surveys
of different configurations count as independent; an identical repeat replaces the old reading. Cave
walls are re-measured: a newer cave map replaces an older one unless the older came from a better
analyser and is still current. So a mined-out deposit weakens and new voids appear after the next
survey. Measurements older than five days are marked outdated.

**Camera.** Left drag turns around the target, right drag moves across the screen, Shift + right drag
(or the middle button) moves up and down, the wheel zooms, a click selects; "Reset" frames the whole map
and "Centre" the selected structure. The camera stays usable while points arrive. Heights are
exaggerated for wide, shallow surveys (the factor is shown).

**Clearing the map.** The paintbrush button in the top right corner of the view clears the station's
3D map of the current dimension after a confirmation: every point and structure is removed for good,
the survey log stays. It is unavailable while a survey is running.

**Waypoints.** "Place waypoint" uses the official APIs: JourneyMap (client API waypoints), FTB Chunks
(client waypoint manager) and Xaero's Minimap (its waypoint set, saved like its own screen does; Xaero's
World Map shows these). With one map mod installed the waypoint goes there, with several the player
chooses. Xaero's World Map alone has no waypoints of its own, which the screen says. The waypoint has the
structure's name, coordinates, dimension and colour; approximate positions are marked "~". A waypoint
with the same name and position is not added twice, and a waypoint is only reported as placed when the
map mod accepted it.

## Physical model

The model never propagates a wave block by block. It sums closed-form responses of a bounded set of
reflectors over a pixel grid and depth bins, then processes the synthetic record the way an interpreter
would. One block is one metre.

### Rock properties

Each material has a density ρ (g/cm³), a P-wave velocity V (m/s) and a quality factor Q.

- Acoustic impedance: **Z = ρ·V**.
- Normal-incidence reflection coefficient: **R = (Z₂ − Z₁) / (Z₂ + Z₁)**.
- A deposit is not pure mineral: its ore mineral is mixed into the host rock at a grade (35 % for the
  ore body, 15–20 % in a MEDIUM/LARGE halo). Density mixes arithmetically, velocity by Wyllie's time average (1/V = Σφᵢ/Vᵢ), attenuation
  by volume (1/Q = Σφᵢ/Qᵢ). Because of the time average, a very dense but slow mineral (cinnabar,
  galena) raises impedance less than a lighter, faster one (magnetite): density alone does not decide
  the contrast. Coal is lighter and slower than its host and reflects with reversed polarity.

Typical rock values: stone 2.65 g/cm³ / 5500 m/s, deepslate 2.80 / 6100, granite 2.65 / 5800,
diorite 2.85 / 6300, andesite 2.60 / 5300, tuff 1.90 / 2600. Ores have **no fixed values**: their
conditional minerals are derived from game data, see [Ore identification](#ore-identification).
Datapacks override anything (see below).

### What reflects

- **Deposits.** The ore body itself (MEDIUM/LARGE lenses are 20–60 blocks wide and 3–7 thick) plus a
  **mineralised halo**: altered host rock with 15 % (MEDIUM) or 20 % (LARGE) ore mineral and less than
  half the host's Q, 4.2 times the body's radius plus 25–34 blocks. Such lenses hold hundreds of
  thousands of units in a few thousand blocks — massive ore — and the halo values were calibrated so
  that their zones compete with the real overworld cave field. Real ore systems sit in such zones; they make a narrow
  lens detectable while keeping its exact blocks unresolved. The halo is a model property only and
  places no blocks. TINY deposits have no halo, SMALL ones a faint one.
- **Natural heterogeneities**, deterministic from the seed per 512-block province: dense diorite-like
  intrusions (positive, large, low absorption), low-velocity tuff lenses (negative), fractured zones
  (small contrast, strong absorption) and steep contacts between granite- or andesite-rich units (long
  narrow reflectors).
- **Caves.** The generator's final density function is evaluated on a coarse lattice (no chunk is
  read); a point at or below zero is open. Its filling is weighted by its contrast with rock: air 1,
  water about 0.8, lava little (in the Nether, lava's impedance is close to netherrack's). Only a
  change with depth reflects: a depth bin answers with R ≈ −1.25·Δφ, the jump of the void fraction
  between the quarter wavelengths above and below (linearised with ρ ∝ 1−φ, V ∝ (1−φ)^1.5). Each jump
  is shrunk by the Wiener factor Δ²/(Δ² + σ²), σ² being the binomial sampling variance of the probed
  averages, so lattice noise does not turn into reflections. Minecraft caves are irregular voids
  smaller than the regional wavelength: only 10 % of their response arrives coherently, a further
  5 % adds incoherent scattering noise, and the void fraction raises the absorption attribute.
  Openings above a column's first rock are sky or sea and are not counted. Player tunnels are not
  seen.
- **Layered background**: the top of deepslate (Y ≈ 4) and of bedrock.

### Response of a reflector

For a source frequency f (20 Hz regional, 50 Hz detailed) and host velocity V:

- Wavelength **λ = V/f**; first Fresnel zone **r = √(λd/2 + λ²/16)** at depth d.
- **Thin bed** (Widess): a layer thinner than λ/4 returns about **4πh/λ** of a single interface;
  thicker bodies produce a top and a base reflection of opposite sign.
- **Footprint**: the lens (semi-axes rₓ, r_z) and the resolution disc (radius σ) are replaced by
  Gaussians of equal second moment — a disc of radius r has variance r²/4 per axis — and convolved:
  √(sₓ/(sₓ+s) · s_z/(s_z+s)) · exp(−½(u²/(sₓ+s) + v²/(s_z+s))), sₓ = rₓ²/4, s = σ²/4. A body much
  wider than the resolution returns its full R, a small one r²/(r² + r_F²), its share of the Fresnel
  zone.
- **Absorption** along the two-way path: **exp(−π·f·t/Q)**, t = 2d/V.

### Noise and processing

- Spherical divergence is corrected, which multiplies ambient noise by the path length:
  σ(d) = σ₀·(d + 20)/100 · environment / √N, where N is the number of independent receivers. Rain
  (×1.25), thunder (×1.6) and soft ground under the station (up to ×1.8) raise it.
- Noise is a deterministic function of the survey configuration (station position, geophones, programme,
  radius, conditions) and the seed: the same configuration has the same errors.
- **Background removal**: the per-depth median over the area is subtracted. It removes the layered
  horizons and the average cave porosity; what remains are lateral differences.
- **Migration**: each depth bin is summed laterally with a Gaussian of half the Fresnel-limited
  resolution, as migration collapses energy over its aperture. Signal stays, independent noise drops.
- **Background variability**: each depth bin is judged against its own surroundings. Where the
  geology varies more than the noise — a cave-riddled level, the soft Nether rock — the 30th
  percentile of |residual/σ| exceeds that of pure noise, and the bin's σ is widened to it. An anomaly
  is a point that stands out from its background, not merely from the instrument noise; a target
  covering most of a detailed map does not count as background.
- For every pixel the depth bin with the largest residual-to-noise ratio is kept: its amplitude (sign =
  polarity), noise, height, absorption attribute and lateral uncertainty.
- **Anomalies** are local maxima above a detection threshold of 4.4σ (regional) or 4.5σ (detailed),
  thresholds chosen for the number of independent picks so that a map of pure noise shows none. The
  readout's "heterogeneity" percentage is a logistic function of the intensity around that threshold.

### Geophones and accuracy

The station's own sensor is a zero-offset receiver. Each geophone at offset x adds:

- **Illumination**: a source–receiver pair images the area around its common midpoint, wider for larger
  offsets: w = exp(−½·|p − m|²/(x/2 + 48)²).
- **Independent receivers**: N = (Σw)² / ΣΣ wᵢwⱼ·exp(−dᵢⱼ²/2L²), L = 24 blocks. A cluster counts as one.
- **Azimuth coverage**: 1 − (largest angular gap)/2π of the receivers around the target.
- **Lateral resolution**: σ = √(r²/(1 + N_geo·(0.3 + 0.7·coverage)) + pixel²/12).
- **Velocity from moveout**: a weighted least-squares line through (x², t²) of t² = t₀² + x²/V²; its slope
  error gives σ_V/V, combined with a 25 % prior. Without offsets the depth is uncertain by about 25 %;
  four spread geophones bring it near 10 %. A borehole inside a detailed survey calibrates the velocity
  to 4 %.
- **Errors in the picture**: a depth error that would push a deep body below the record moves the
  whole body back in, keeping its thickness. Reflectors are shifted by about 0.6σ in a direction fixed per configuration
  and structure, and depths are scaled by one survey-wide velocity factor plus a small local term.
  Stacking surveys of different configurations averages these errors out (inverse-variance weights per
  pixel).

### Data sources

- **Generated terrain**: the confirmed deposit index (authoritative). Where a deposit's chunks are
  loaded, its real remaining ore lowers the ore body's contrast; the halo stays after mining, so a
  worked-out deposit is still an anomaly, only weaker. Datapack deposits and modded ores are included.
- **Unexplored terrain**: the same deterministic plans worldgen and `/locate` use — per-cell material
  lottery, tier resolvers and datapack rule slots. Regional surveys use the planned centre directly;
  detailed surveys move the lens into its source biome and keep it only if its worldgen plan wins
  arbitration. A prediction whose source chunk already generated without confirming it is discarded.
- TINY/SMALL are taken only from the index and only in detailed surveys; regional maps would drown in
  them.

### Calibration

`OverworldCalibrationGameTests` builds the vanilla overworld noise router for the world seed, probes
its real caves at three sites and places reference iron deposits (with the iron mineral the ore table
derives): without geophones, regional surveys show LARGE deposits at 7–14σ and detailed surveys MEDIUM
ones at 4–7σ, while caves and natural structures still produce anomalies. Geophones raise these
figures. In the Nether, a forecast LARGE gold deposit shows at about 56σ, and a level 3 survey places
its lens within about 15 blocks and names the ore at over 99 %.

## Ore identification

The station and the exploration drill share one identification model; a core is simply a
measurement with much smaller errors.

**Ore table.** Built per world from what the game records, for every ore of the `c:ores` tags and every
ore that worldgen recorded, vanilla or modded:

- **class** from the common tags of the products: raw materials or ingots → metal ore, gems → gem ore,
  dusts → soft ore, otherwise other (fuels and the like). A material such as `netherite_scrap` is also
  looked up without its qualifier;
- **relative hardness**: percentile rank of the mining tier (`needs_stone/iron/diamond_tool`) and then
  the destroy time among all ores;
- **generation depth** and **prevalence** from the recorded ore features: the depth index of the mean
  generation height, and the share of the ore's placement attempts among the dimension's ores.

The conditional mineral of an ore follows only from these traits (metal ores dense and fast, gem ores
fast and light, soft ores slower, other ores light and slow; harder and deeper-formed ores denser, faster
and less absorbing). **Rarity is prevalence, not density**: it only sets the prior probability. Ores
without recorded generation get a neutral depth and a quarter of the rarest share; ores that generate
in other dimensions stay possible at that low prior. Datapack and API rock physics entries for an ore
block replace its computed mineral. The table is rebuilt when tags, rock physics data or recorded
generation change.

**Measurement.** Each target is traced to the model element behind its strongest event — a deposit,
a natural heterogeneity or the cave field; a weak event that no single element explains is noise. The
measurement estimates the source's intrinsic contrast (its reflection coefficient at ore-body grade),
its absorption (ln Q_host/Q_source) and its depth, with errors that shrink with the signal-to-noise
ratio and by 0.72 per scanner level; a core has laboratory errors. The errors are deterministic per
survey configuration, like all survey noise.

**Posterior.** Every ore (prior: its prevalence; likelihood: the fit of contrast and absorption, and of
the depth to its generation range) and every natural kind (dense massif, porous lens, fractured zone,
rock contact — integrated over their natural range — and caves) is weighed; the result is normalised.
Similar ores share the probability, the commoner one getting more; a better scanner narrows the errors
until they separate. A core rules out natural sources. Up to five candidates above 2 % are listed.

## Scanner levels

One kind of upgrade module, installed up to four times. Each level adds one improvement, and every
level narrows identification and speeds up the survey by 7 %.

| Level | Improvement | Effect |
|---|---|---|
| 0 | — | ore classes only (metal, gem, soft, other), target footprint |
| 1 | range | regional radius 4096 (configurable), detailed radius ×1.5; ores are named |
| 2 | depth | up to 35 % less noise at depth; regional surveys resolve depth; target outlines and orientation |
| 3 | accuracy | position error ×0.5, velocity error ×0.6; the deposit's own lens with its thickness |
| 4 | detail | map grid 25 % finer: denser point cloud, close deposits separated |

Identification chances in a vanilla overworld at a signal-to-noise ratio of 8 (mean over all ores, if
names were shown): survey 31 % → 40 % → 53 % → 67 % → 80 % from level 0 to 4; core 84 % → 94 % →
98 % → 99.7 % → 100 %. Old stations with a range module load as level 1.

## Cave analyser

A second module kind, installed independently of the scanner (up to three times). It probes voids on a
lattice around the station: in loaded chunks it reads the blocks at the lattice points (so player
tunnels and carvers count), elsewhere the generator's density function. Connected open probes form
caves; their walls are placed where the density changes sign, plus the analyser's positioning noise.
The map is never finer than the lattice and nothing is completed beyond what was probed.

| Level | Probe spacing | Radius | Wall error | False readings | Connections | Speed |
|---|---|---|---|---|---|---|
| 1 | 8 blocks | 48 | ±2.5 | 0.4 % of rock probes | suspected only | half the pulse |
| 2 | 5 blocks | 80 | ±1.2 | 0.15 %, half of pure false voids rejected | resampled every 2 blocks | ¾ of the pulse |
| 3 | 3 blocks | 112 | ±0.5 | 0.04 %, 85 % rejected | resampled every block | with the pulse |

A void crossing n probes is detected with probability 1 − e^(−n/n₀) (n₀ = 6, 3, 1.2), so small voids are
often missed by a weak analyser. Two caves separated by a single rock probe are a suspected connection;
from level 2 the analyser resamples a box around the gap and confirms it when it finds an open path — a
coarse step can miss a narrow passage. Each cave reports its estimated centre, depth, extent, open
volume, shape (small void, tunnel, chamber, cavern), filling and connections. A slower analyser keeps the
station processing after its impacts end.

## Performance and threading

- No chunk is loaded or generated. Only the confirmed index, already-loaded chunk data and the saved
  `Status` field of unloaded chunks (asynchronous chunk scanner) are read.
- World state is read on the server thread within a per-tick budget (2 ms by default). Forecasting,
  cave probing and the model run on Minecraft's background executor over immutable snapshots. A regional
  survey with the upgraded 4096-block radius keeps every service tick far below the budget of a server
  tick (see `SeismicSurveyGameTests`).
- Forecasts are cached per world (4096 cells, weak world reference) and dropped on config or datapack
  reloads. Survey work is cancelled when its world unloads or the server stops.
- Clients receive only processed measurements and interpretations, and only what the survey fronts
  have revealed: map samples (position quantised to 1/8 block, spread, confidence, colour) and objects
  (estimated positions and shapes with their errors and identification probabilities). Deposit
  identities, true positions and model bodies never leave the server. Only a player with the equipment's
  screen open receives its map; only a player who may build there can start or stop a survey.
- The map is sent incrementally: every change gives a 32-block bucket a new revision, and each viewer
  receives the buckets changed since its cursor, oldest first, within a per-tick budget (6000 samples by
  default). Reopening the screen sends only what changed meanwhile; the client keeps maps of the session.
- The cave lattice is read on the server thread in budgeted slices; the analysis runs on the worker with
  the seismic model; connection checks run on the server thread within the same budget.
- The view draws each bucket as its own GPU buffers (one per layer, plus a quarter copy for distance),
  rebuilds only changed buckets (a few per frame), skips buckets outside the view frustum, draws the
  reduced copy for everything beyond the point budget, and blends additively so nothing is sorted.
  Quality (client config `ores_and_drills-client.toml`, or the button) sets points per measurement, the
  budget (60k/160k/320k points) and the glow pass.

## Storage

- `ores_and_drills_geology` (per dimension): geophone positions and boreholes.
- `ores_and_drills_seismic_<station id>` (in the overworld's data folder): one station's surveys —
  configuration, scanner level, the processed map (intensity at 1/64 σ, noise on a log scale) and the
  interpreted targets. Surveys keep what they measured even after the deposit is mined.
- `ores_and_drills_lidar_<station id>` (in the overworld's data folder): the station's 3D maps, one
  per dimension — samples with their averaging state, objects and covered volumes, at most 250,000
  samples and 1024 objects per dimension (the parts farthest from the latest survey go first). The
  exploration drill keeps its 3D log (at most 16,000 samples) in its block entity.
- The geology data and the 3D maps write `Format=1`, the survey archive `Format=2` (targets instead of model bodies);
  both reject other versions, so archives from the earlier format are dropped. A survey interrupted by
  a restart is resubmitted from the station's saved configuration and, being deterministic, ends with
  the same result.
- Scanner and analyser levels are stored in the station and drill block entities and carried by their
  items.

## Configuration

`config/ores_and_drills-seismic.toml`: radii (`regional_radius` 2048, `detailed_radius` 512,
`upgraded_regional_radius` 4096 from scanner level 1; `regional_upgrade` grants it to every station; level 1 also multiplies the detailed radius by 1.5), durations (1200 and 600 ticks), energy use and
buffer, geophone limit, saved surveys per station, server-thread budget, cave probe budget, and the
exploration drill's energy, speed and depth; `[lidar_map]`: `max_samples`, `max_objects`, `stale_days`
(5) and `sync_samples_per_tick`. The client's `config/ores_and_drills-client.toml` holds the view
quality.

## Rock physics datapacks

`data/<namespace>/rock_physics/<name>.json`:

```json
{
  "entries": [
    {"targets": ["create:limestone"], "density": 2.71, "velocity": 6000, "quality": 120},
    {"targets": ["#c:ores/uranium"], "density": 6.5, "velocity": 4500, "quality": 80}
  ]
}
```

Block entries win over tag entries, later files over earlier ones. Mods can register defaults in code
with `RockPhysics.register(blockId, new RockProperties(ρ, V, Q))`.

## Limitations

- The model is a sum of analytic responses, not a wave simulation: no multiples, mode conversion,
  diffraction tails or anisotropy, and caves are averaged on a lattice of 16–48 blocks.
- Natural heterogeneities are synthetic (seeded), not read from placed granite/diorite/tuff blobs; caves
  come from the base terrain and ignore carvers and player tunnels.
- Remaining ore is known only for deposits whose chunks are loaded during the survey; elsewhere the
  index's block count stands in.
- Regional forecasts place an unexplored lens at its planned centre, up to the relocation distance (64
  blocks) from where worldgen will put it.
- Halo strength and cave coherence are game-model parameters calibrated on vanilla terrain; heavily
  modded terrain generators may need other values.
- Ore minerals are conditional: they are consistent within a world and between the survey and the
  drill, not laboratory values. Two ores of one class, hardness and depth cannot be told apart by
  measurement at all; only their prevalence separates them.
