package applygray.api;

import net.minecraft.util.ResourceLocation;

public class ApplyGrayAPI {

    public static final String MODID = "applygray";
    public static final String MOD_NAME = "ApplyGray";

    public static ResourceLocation id(String path) {
        return new ResourceLocation(MODID, path);
    }
}
