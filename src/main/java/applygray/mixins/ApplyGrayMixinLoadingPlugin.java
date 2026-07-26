package applygray.mixins;

import net.minecraftforge.common.ForgeVersion;
import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin;
import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin.MCVersion;
import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin.Name;
import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin.SortingIndex;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;
import zone.rong.mixinbooter.IEarlyMixinLoader;

import java.util.Collections;
import java.util.List;
import java.util.Map;

@Name("ApplyGrayMixinLoadingPlugin")
@MCVersion(ForgeVersion.mcVersion)
@SortingIndex(1001)
@SuppressWarnings("deprecation") // MixinBooter needs the early loader for the vanilla GuiScreen mixin.
public class ApplyGrayMixinLoadingPlugin implements IFMLLoadingPlugin, IEarlyMixinLoader {

    private static final Logger LOGGER = LogManager.getLogger("applygray");
    private static final String CCL_NOTCH_SRG_PROPERTY = "net.minecraftforge.gradle.GradleStart.srg.notch-srg";
    private static final String CCL_CSV_DIR_PROPERTY = "net.minecraftforge.gradle.GradleStart.csvDir";
    private static final String APPLY_GRAY_CCL_NOTCH_SRG_PROPERTY = "applygray.ccl.notchSrg";
    private static final String APPLY_GRAY_CCL_CSV_DIR_PROPERTY = "applygray.ccl.csvDir";

    @Override
    public String[] getASMTransformerClass() {
        return null;
    }

    @Override
    public String getModContainerClass() {
        return null;
    }

    @Nullable
    @Override
    public String getSetupClass() {
        return null;
    }

    @Override
    public void injectData(Map<String, Object> data) {
        // Set these after Unimined's startup agent has finished but before CCL constructs its dev remapper.
        boolean configured = copyRunProperty(CCL_NOTCH_SRG_PROPERTY, APPLY_GRAY_CCL_NOTCH_SRG_PROPERTY);
        configured |= copyRunProperty(CCL_CSV_DIR_PROPERTY, APPLY_GRAY_CCL_CSV_DIR_PROPERTY);
        if (configured) {
            LOGGER.info("Configured deferred legacy CodeChickenLib development mappings.");
        }
    }

    private static boolean copyRunProperty(String targetProperty, String sourceProperty) {
        if (System.getProperty(targetProperty) != null) return false;

        String value = System.getProperty(sourceProperty);
        if (value != null && !value.trim().isEmpty()) {
            System.setProperty(targetProperty, value);
            return true;
        }
        return false;
    }

    @Override
    public String getAccessTransformerClass() {
        return null;
    }

    @Override
    public List<String> getMixinConfigs() {
        return Collections.singletonList("mixins.applygray.default.json");
    }

}
