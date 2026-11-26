# Credits

## Licence

From the first versions after Fieldframe 0.1.1 (Forge 1.20.1, NeoForge 1.21.1) and 0.3.3 (NeoForge 26.1.2),
Fieldframe Frameworks 0.1.2 (1.20.1, 1.21.1) and 0.1.3 (26.1.2), and Fieldframe Transit 0.2.0:

- **Code** is licensed under the GNU Lesser General Public License, version 3 or (at your option) any later version
  (`LGPL-3.0-or-later`). The LGPL text is in [LICENSE](LICENSE); it builds on the GNU General Public License version 3,
  whose text is in [COPYING](COPYING). Both ship in the jar's `META-INF` with this file.
- Art licence: the mod's own art is LGPL-3.0-or-later, the same as the code.
- **Third-party and derived art** keeps its own licence, listed below.

Those versions and all earlier ones were released as All Rights Reserved.

This covers Fieldframe and its add-ons in this repository, Fieldframe Frameworks (`frameworks/`) and Fieldframe
Transit (`transit/`), and the example add-ons in `examples/`.

## Credits and third-party art

Third-party and derived art is listed here and per file in `docs/provenance.md`. Fieldframe Transit's textures and
some module and machine faces are derived from Minecraft textures (Mojang); those keep Mojang's terms and are not
covered by the LGPL.

- **Emasher** — Engineer's Toolbox (Minecraft 1.7.10) inspired Fieldframe's concept: modular faces on one block.
  Fieldframe is an independent reimplementation; it contains no code, textures, names of items or text from it.
  See `docs/inspiration_matrix.md` for what was taken as an idea and what was changed.
- Minecraft Forge and NeoForge teams; ModDevGradle; ParchmentMC mappings.
- KubeJS, Rhino and Architectury (LatvianModder and contributors), used only as optional dependencies.
- Textures are drawn procedurally by `tools/gen_textures.py`.
- **Sky Welch (voxelcarrot)**: EnderTech. Fieldframe's Exchanger, Charge Pad, Health Pad and Ender Tank mechanics are
  reimplemented from its ISC-licensed source. (Its original textures are no longer shipped; Fieldframe draws its own.)
- **pixlepix** (textures by Blorph): FLORA, and **dmf444**: FLORA Reborn. Fieldframe's fluid armor and Armor Infuser
  mechanics are reimplemented from their sources (no code copied). The original FLORA armor, infuser and fluid
  textures by Blorph (MIT per the CurseForge pages) are included in `shared-classic/`. See `docs/provenance.md`.
- **Rok**: Create Hypertube (Apache License 2.0, https://www.curseforge.com/projects/1281336). Fieldframe Transit's
  tube code is ported from it and changed for newer Minecraft and for RF power instead of Create's stress.
  Transit as a whole stays LGPL-3.0-or-later; the ported code also stays under Apache 2.0, whose text ships in
  Transit's jar as `META-INF/LICENSE-create_hypertube`.
- **Metallurgy 4: Reforged** (required dependency; the owner's fork): all common metals, ores, dusts and alloys.
  Fieldframe mirrors its alloyer and crusher recipes as data.

- Ingot, nugget, dust, plate and gear item art is recoloured from the **Painterly Pack** templates by **Rhodox &
  friends** (Painterly Continuation Project), from the owner's Painterly Lapana and Gau-Ha editions.

## Third-party textures used as bases
These shipped textures start from another mod's texture: its light and dark are mapped onto Fieldframe's own colours
and then edited by hand. Details per file in `docs/provenance.md`.

- Ender alloy block: from *queens_steel_block* in **Velvet Advanced Alloys** by **Velvet Crowe** (MIT, as declared
  by the mod). Copyright (c) Velvet Crowe.
- Signal alloy block: from *blazegold_block* in **Just Dire Things** by **Direwolf20** and contributors (MIT).
  Copyright (c) 2023 Direwolf20-MC.
- Lumen alloy block: from *electrum_block* in **Modern Industrialization** by the **AztechMC** team (textures by
  GregoriusT, Falkory and others; assets CC0). Its shading follows Minecraft's own gold block.
- Arcane fluid: from *molten_shiny* (still and flowing) in **Tinkers' Construct** by **SlimeKnights** (code and
  textures MIT). Copyright (c) 2022 SlimeKnights.
- Worn fluid armor (all four metals): from the *sentient* armor in **NeoVitae** by **breakinblocks** (MIT), which
  continues **Blood Magic** by **WayofTime**; the sentient armor art is by **Jamiscus** and **LadyMaehem**, and the
  parts that come from Blood Magic are licensed CC BY 4.0 (https://creativecommons.org/licenses/by/4.0/). Recoloured
  per metal, with vials and fluid channels added.

The MIT licence for the textures above:

> Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
> documentation files (the "Software"), to deal in the Software without restriction, including without limitation the
> rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit
> persons to whom the Software is furnished to do so, subject to the following conditions:
>
> The above copyright notice and this permission notice shall be included in all copies or substantial portions of the
> Software.
>
> THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE
> WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
> COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
> OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
