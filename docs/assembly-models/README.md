# Assembly art sources

`drill_assembly_table.bbmodel` is the source of the two-block assembly table. Its 29 cuboids
were created and UV-mapped through Blockbench MCP. The block JSONs are split at X=16; the
full item model is centered separately for inventory rendering.

The user-requested [Minecraft Style Guide](https://www.blockbench.net/wiki/guides/minecraft-style-guide/)
takes precedence over a generic high-cube-count modeling workflow. Small seams, fasteners,
vents, hazard stripes and the blueprint are texture details. Existing finished-drill assets
were not edited.

Each face has an individually sized UV island at one pixel per Blockbench unit. The table's
128 × 128 atlas is painted in Aseprite. Its source and Lua paint script are in
[`../assembly-sprites`](../assembly-sprites). Blockbench MCP `check_model` reports zero issues.
The model's front is north (-Z); the blockstate `facing` stores its long axis, east (+X) in
the unrotated model. Only the main half has a block entity.

The seven components now use flat Aseprite sprites and `minecraft:item/generated` models.
Their obsolete `.bbmodel` files and 3D atlases were removed.
