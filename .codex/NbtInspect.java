import java.io.DataInputStream;
import java.io.File;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Arrays;

import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.chunk.storage.RegionFile;

public final class NbtInspect {
    private static final Pattern REGION_NAME = Pattern.compile("r\\.(?<x>-?\\d+)\\.(?<z>-?\\d+)\\.mca");

    public static void main(String[] args) throws Exception {
        File world = new File(args[0]);
        File[] regions = new File(world, "region").listFiles((directory, name) -> REGION_NAME.matcher(name).matches());
        if (regions == null) return;
        for (File regionFile : regions) {
            Matcher matcher = REGION_NAME.matcher(regionFile.getName());
            if (!matcher.matches()) continue;
            int regionX = Integer.parseInt(matcher.group("x"));
            int regionZ = Integer.parseInt(matcher.group("z"));
            scanRegion(regionFile, regionX, regionZ);
        }
    }

    private static void scanRegion(File regionPath, int regionX, int regionZ) throws Exception {
        RegionFile region = new RegionFile(regionPath);
        try {
            for (int localX = 0; localX < 32; localX++) {
                for (int localZ = 0; localZ < 32; localZ++) {
                    try (DataInputStream in = region.getChunkDataInputStream(localX, localZ)) {
                        if (in == null) continue;
                        NBTTagList entities = CompressedStreamTools.read(in).getCompoundTag("Level")
                                .getTagList("TileEntities", 10);
                        for (int index = 0; index < entities.tagCount(); index++) {
                            NBTTagCompound entity = entities.getCompoundTagAt(index);
                            describeProvider(entity);
                            describeDualExportHatch(entity);
                            describeSunforgeEntity(entity);
                        }
                    }
                }
            }
        } finally {
            region.close();
        }
    }

    private static void describeProvider(NBTTagCompound entity) {
        NBTTagCompound data = entity.getCompoundTag("MetaTileEntity");
        if (!"applygray:me_recipe_map_pattern_provider".equals(entity.getString("MetaId"))) return;
        NBTTagList pool = data.getTagList("BufferPool", 10);
        String provider = "PROVIDER pos=" + entity.getInteger("x") + ',' + entity.getInteger("y") + ',' +
                entity.getInteger("z") + " export=" + data.getBoolean("Export") + " buffers=" + pool.tagCount();
        if (entity.getInteger("x") == 631 && entity.getInteger("y") == 27 && entity.getInteger("z") == -1103) {
            NBTTagList patterns = data.getTagList("LazyRecipePatterns", 10);
            System.out.println(provider + " cachedPatterns=" + patterns.tagCount());
            for (int patternIndex = 0; patternIndex < patterns.tagCount(); patternIndex++) {
                NBTTagCompound pattern = patterns.getCompoundTagAt(patternIndex);
                NBTTagCompound binding = pattern.getCompoundTag("Binding");
                System.out.println("  PATTERN #" + (patternIndex + 1) + " map=" + pattern.getString("RecipeMap") +
                        " key=" + pattern.getString("RecipeKey") + " fp=" + binding.getString("Fingerprint"));
                System.out.println("    inputs=" + pattern.getTagList("Inputs", 10) +
                        " alternatives=" + pattern.getTagList("Alternatives", 10) +
                        " outputs=" + pattern.getTagList("Outputs", 10));
            }
        }
        boolean printed = false;
        for (int index = 0; index < pool.tagCount(); index++) {
            NBTTagCompound buffer = pool.getCompoundTagAt(index);
            if (!buffer.hasKey("Signature", 10)) continue;
            NBTTagCompound signature = buffer.getCompoundTag("Signature");
            NBTTagCompound binding = signature.getCompoundTag("RecipeBinding");
            if (!printed) {
                printed = true;
                System.out.println(provider);
            }
            System.out.println("  #" + (index + 1) + " map=" + signature.getString("RecipeMap") +
                    " fp=" + binding.getString("Fingerprint") + " locked=" + buffer.getBoolean("recipeLocked"));
            System.out.println("    items=" + buffer.getCompoundTag("Items").getTagList("Items", 10) +
                    " big=" + buffer.getCompoundTag("Items").getCompoundTag("BigStackSize"));
            System.out.println("    fluids=" + buffer.getTagList("Fluids", 10) +
                    " circuits=" + buffer.getTagList("CircuitSlots", 10));
        }
        if (!printed) System.out.println(provider);
    }

    private static void describeSunforgeEntity(NBTTagCompound entity) {
        int x = entity.getInteger("x");
        int y = entity.getInteger("y");
        int z = entity.getInteger("z");
        if (x != 631 || z != -1103 || (y != 28 && y != 27)) return;

        NBTTagCompound data = entity.getCompoundTag("MetaTileEntity");
        System.out.println("SUNFORGE_ENTITY pos=" + x + ',' + y + ',' + z +
                " metaId=" + entity.getString("MetaId") + " entityKeys=" + entity.getKeySet());
        System.out.println("  dataKeys=" + data.getKeySet());
        System.out.println("  data=" + data);
    }

    private static void describeDualExportHatch(NBTTagCompound entity) {
        if (!"applygray:me_dual_hatch.export".equals(entity.getString("MetaId"))) return;

        NBTTagCompound data = entity.getCompoundTag("MetaTileEntity");
        System.out.println("DUAL_EXPORT pos=" + entity.getInteger("x") + ',' + entity.getInteger("y") + ',' +
                entity.getInteger("z") + " working=" + data.getBoolean("WorkingEnabled") +
                " itemBuffer=" + data.getTagList("ItemBuffer", 10) +
                " fluidBuffer=" + data.getTagList("FluidBuffer", 10) +
                " node=" + entity.getCompoundTag("applygray_mte_node"));
    }
}
