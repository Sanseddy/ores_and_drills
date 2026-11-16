# Assembly artwork sources

The seven components are flat 16 × 16 pixel-art items. Their cold blue-gray palette,
dark outlines and restrained copper/cyan accents keep the silhouettes readable in inventory slots.

| Component | Readable features |
| --- | --- |
| Drill frame | Rectangular metal frame with an open center |
| Mechanical drive | Two steel gears and a projecting shaft |
| Electric motor | Steel housing and three copper winding bars |
| Drill head | Mounting collar and tapered steel cutting head |
| Reinforced drill head | Steel head with cyan cutting segments |
| Fluid manifold | Copper outlets and a red valve |
| Precision control | Green circuit board with gold contacts |

`components.aseprite` / `components.png` contain the contact sheet at original pixel size.
`components-preview.png` is a 4× nearest-neighbor export for viewing.
`components-inventory.aseprite` and `components-inventory-preview.png` show the same items
against vanilla inventory-slot gray. Each component also has an individual editable `.aseprite` source.
The table texture source is `drill_assembly_table.aseprite`, a 128 × 128 UV atlas for the two-block model.

Run these from the repository root to repaint production PNGs using Aseprite:

```powershell
aseprite -b --script-param root=. --script docs/assembly-sprites/paint-components.lua
aseprite -b --script-param root=. --script docs/assembly-sprites/paint-table.lua
aseprite -b --script-param root=. --script docs/assembly-sprites/paint-interface.lua
```

The first script contains explicit drawing commands and the palette. The second uses `table-uv.json`,
which comes from the actual Blockbench face UVs. Geometry changes require updating that UV data
and the exported model JSONs together. Keep one texture pixel per model unit and avoid stretched UVs.

`drill_assembly_interface.aseprite` is the layered source for `textures/gui/drill_assembly.png`.
Its 256 × 256 atlas contains a 196 × 215 vanilla-style container panel, standard 18px recessed
slots, a 26px output slot, and separate white progress overlays. `paint-interface.lua` regenerates
it in Aseprite with the Minecraft container palette. Slot coordinates match `AssemblyMenu`;
the screen retains native Minecraft buttons, font and tooltips.

The recipe selector sits inside the main panel at x=8, with three 24px slot states
(normal, selected, hovered). Three result icons are visible at a time, with compact
10px arrow buttons above and below. The crafting grid begins at x=48; the player
inventory begins at y=134.

`iron_rod.aseprite` is the separate 16 × 16 iron-rod source. Regenerate it and its production PNG with `aseprite -b --script-param root=. --script docs/assembly-sprites/paint-iron-rod.lua`.
