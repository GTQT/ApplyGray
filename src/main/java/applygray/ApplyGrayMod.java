package applygray;

import applygray.api.ApplyGrayAPI;
import applygray.common.ApplyGrayMetaTileEntities;
import applygray.common.ApplyGrayRecipes;
import applygray.integration.ApplyGrayIntegrationBootstrap;
import applygray.integration.ae2.rules.RecipePatternRules;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPostInitializationEvent;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(modid = ApplyGrayAPI.MODID,
     name = ApplyGrayAPI.MOD_NAME,
     acceptedMinecraftVersions = "[1.12.2,1.13)",
     version = ApplyGrayInternalTags.VERSION,
     dependencies = "required:forge@[14.23.5.2847,);" +
             "required-after:gregtech;" +
             "required-after:ae2;" +
             "after:jei@[4.15.0,);")
public class ApplyGrayMod {

    public static final Logger LOGGER = LogManager.getLogger(ApplyGrayAPI.MODID);

    @Mod.Instance
    public static ApplyGrayMod instance;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        LOGGER.info("Loading Applied Energistics 2 Supergiant integration for GregTech");
        RecipePatternRules.initialize(event.getModConfigurationDirectory());
        ApplyGrayIntegrationBootstrap.init();
        ApplyGrayMetaTileEntities.init();
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        ApplyGrayRecipes.init();
    }

    @Mod.EventHandler
    public void postInit(FMLPostInitializationEvent event) {
        ApplyGrayRecipes.postInit();
    }
}
