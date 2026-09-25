# AnvilCraft-Fluid

An addon for [AnvilCraft](https://github.com/Anvil-Dev/AnvilCraft) on [NeoForge 1.21.1](https://neoforged.net/) that adds a complete **molten-fluid system**: metals and gems melt into fluids, pour back into blocks, grow ores and brew royal steel.

> ⚠️ **Work in progress**: recipes and numbers are still being tuned. In-game behaviour and the in-game guide are authoritative.

## Content

### 20 fluids

| Group | Fluids |
| --- | --- |
| **Molten gems** (6) | ruby, sapphire, topaz, emerald, quartz, amethyst |
| **Molten metals** (11) | iron, gold, copper, tungsten, royal steel, lead, silver, tin, zinc, titanium, uranium |
| **Functional fluids** (3) | frost fluid, ember fluid, cursed gold fluid |

Every fluid ships with its own **fluid block, cauldron and bucket**, and can be poured into the large cauldron, a cauldron or a fish tank.

### Recipes

| Gameplay | Mechanism | Example |
| --- | --- | --- |
| **Melting** | `anvilcraft:super_heating` | iron block (or 9 iron ingots) → 1000mB molten iron |
| **Cooling** | `anvilcraft:solid_liquid` | a full cauldron of molten fluid + a falling anvil → the matching block |
| **Ore conversion** | our `anvilcraft_fluid:multi_fluid_mixing` | 10mB molten ruby + 250mB molten metal → the **deepslate** ore; use molten sapphire for the **stone** ore |
| **Alloys** | our `multi_fluid_mixing` | 1000mB molten iron + 1000mB of any molten gem (ruby/topaz/sapphire/emerald) + 1 diamond → molten royal steel; 10mB molten topaz + 1000mB molten iron → magnet block |
| **Functional fluids** | our `multi_fluid_mixing` | 1000mB powder snow + 1 frost metal nugget → frost fluid; 1000mB oil + 1000mB lava + 1 ember metal nugget → ember fluid |
| **Time warp** | `anvilcraft:time_warp` | netherite ingot + lava → ancient debris; netherite scrap + molten tungsten → ancient debris; a full cauldron of frost fluid + royal steel (block/ingot/nugget) → frost metal (block/ingot/nugget) |

All recipes run in the **large cauldron**, triggered by an anvil impact, exactly like upstream AnvilCraft.

### Per-fluid behaviour

| Fluid | Effect |
| --- | --- |
| Frost fluid | Being inside feels like powder snow: freeze ticks build up, freeze damage, frosted vignette. In a cauldron it strips every enchantment (250mB each) and turns them into liquid enchantment |
| Ember fluid | Sets mobs on fire, burns them for heavy damage and destroys ordinary items; fire-immune items are unharmed, and ember gear with the reforging property is actually repaired — much faster than lava |
| Cursed gold fluid | Inflicts weakness on anything inside; produced 1:1 when molten gold washes curses off items |
| Molten uranium | Inflicts wither on anything inside |

Nine of the molten fluids **solidify on contact with water**: molten gems turn into granite, diorite, andesite or calcite, while molten iron, gold, copper and tungsten turn into the matching ore (flowing molten metal does not react). The numbers above are adjustable in the mod config.

### Integration

- **Ageratum guide**: our custom recipe type ships with its own guide display component; the pages live under "AnvilCraft: Fluid Expansion". Ageratum is **optional** — the game runs fine without it (the relevant classes are never loaded).
- **JEI**: custom recipe category plus display-only pseudo recipes, and info pages for frost/ember fluid. JEI is optional too.

## Environment

| Dependency | Version |
| --- | --- |
| Minecraft | 1.21.1 |
| NeoForge | 21.1.241 |
| Kotlin for Forge | 5.9.0 |
| AnvilCraft | 1.6.0+snapshot.2339 |
| Anvil Lib | 2.0.0+snapshot.534 |
| JDK | 21 |
| Gradle | 8.14.5 (wrapper) |

Optional (compile-time only, installed by the player at runtime): Ageratum `0.0.1+build.121`, JEI `19.27.0.340`.

## Build

```bash
./gradlew build          # output in build/libs/
./gradlew runClient      # launch the dev client
./gradlew runData        # generate data into src/generated/resources
```

## Development notes

- **All text is Chinese-only** for now (`src/main/resources/assets/anvilcraft_fluid/lang/zh_cn.json`); English clients will see raw translation keys.
- Guide sources live in `src/main/resources/assets/anvilcraft/ageratum/zh_cn/100_anvilcraft_fluid/`.
- Longer design notes (Chinese) are in `铁砧工艺_流体扩展_开发计划.md` at the repository root.

## License

Code and assets are released under the MIT license, see `LICENSE` and `ASSETS_LICENSE`.
