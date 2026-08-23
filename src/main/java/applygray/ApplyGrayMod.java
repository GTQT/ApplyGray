package applygray;

import applygray.api.ApplyGrayAPI;
import applygray.common.ApplyGrayCommonProxy;
import applygray.common.ApplyGrayMetaTileEntities;
import applygray.common.ApplyGrayRecipes;
import applygray.integration.ApplyGrayIntegrationBootstrap;
import applygray.integration.ae2.rules.RecipePatternRules;
import applygray.integration.theoneprobe.TheOneProbeIntegration;
import applygray.mattermanipulator.network.MatterManipulatorNetwork;
import applygray.mattermanipulator.config.MatterManipulatorConfig;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.SidedProxy;
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

    @SidedProxy(clientSide = "applygray.client.ApplyGrayClientProxy",
                serverSide = "applygray.common.ApplyGrayCommonProxy")
    public static ApplyGrayCommonProxy proxy;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        LOGGER.info("Loading Applied Energistics 2 Supergiant integration for GregTech");
        MatterManipulatorConfig.load(event.getModConfigurationDirectory());
        RecipePatternRules.initialize(event.getModConfigurationDirectory());
        MatterManipulatorNetwork.initialize();
        proxy.preInit(event);
        TheOneProbeIntegration.enqueueIMC();
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
