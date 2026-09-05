# ApplyGray

> Applied Energistics 2 (Supergiant) integration addon for GregTech — Minecraft 1.12.2.

ME-enabled GregTech multiblock parts, dynamic pattern providers, the Matter Manipulator
tool chain with its Quantum Uplink, and GregTech-scale quantum storage machines with
native ME access.

> **Notice**: some code in this repository comes from
> [GTLite Core](https://github.com/GregTechLite/GregTech-Lite-Core), licensed under the
> [Apache License 2.0](https://github.com/GregTechLite/GregTech-Lite-Core/blob/main/LICENSE).

## Requirements

- Minecraft 1.12.2
- Forge 14.23.5.2847 or newer
- GregTech — the matching GTQT fork (`gregtech-gtqt-1.12.2-1.9.0.jar`)
- Applied Energistics 2 Supergiant
- Java 25 for development builds (the Gradle toolchain can provision it automatically)

The matching development jars live in `libs/` so the project can be built without a
separate GregTech source checkout.

## Build

```powershell
$env:JAVA_HOME = 'path-to-jdk-25'
.\gradlew.bat clean build
```

The mod jar is generated in `build/libs`.

## License

ApplyGray itself is licensed under **LGPL-3.0-or-later** (see `LICENSE`); third-party
code is used under its own license as noted above.
