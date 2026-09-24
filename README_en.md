# AnvilCraft-Fluid

An addon for [AnvilCraft](https://github.com/Anvil-Dev/AnvilCraft) on [NeoForge 1.21.1](https://neoforged.net/), extending the tech system with fluids.

> ⚠️ **Work in progress**: the repository is still in the pre-development stage and contains no gameplay content yet.

## Planned content

- Multiple molten fluids (molten gems, molten metals, ...)
- Fluid recipes (single-fluid conversion, multi-fluid mixing)
- Fluid reactions (fluid-to-fluid, fluid-to-world)
- Per-fluid special behaviours (enchantment washing, curse cleansing, reversed flow, equipment repair acceleration, redstone signal transmission, ...)

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

## Build

```bash
./gradlew build          # output in build/libs/
./gradlew runClient      # launch the dev client
./gradlew runData        # generate data into src/generated/resources
```

## License

Code and assets are released under the MIT license, see `LICENSE` and `ASSETS_LICENSE`.
