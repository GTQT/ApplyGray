# ApplyGray

ApplyGray is the AE2 and AE2 Fluid Craft integration module split from this GregTech fork.

It owns the AE-enabled MetaTileEntities, the crafting order item, AE/AE2FC mixins, network proxy lifecycle,
wireless structure-building item source, encoded-pattern rendering, color/rotation integration, and recipes.
GregTech itself no longer needs AE2 or AE2FC on its compile or runtime classpath.

## Requirements

- Minecraft 1.12.2
- Forge 14.23.5.2847 or newer
- GregTech (the matching local fork)
- Applied Energistics 2
- AE2 Fluid Craft
- Java 17 for development builds

The matching development jars are included in `libs` so the project can be built independently of GregTech's
`build` directory.

## Build

```powershell
$env:JAVA_HOME = 'path-to-jdk-17'
.\gradlew.bat clean build
```

The mod jar is generated in `build/libs`.
