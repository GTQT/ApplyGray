package applygray.mixins;

import net.minecraftforge.fml.common.Loader;

import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/** Keeps HEI-only mixins out of installations where JEI is not present. */
public class ApplyGrayMixinPlugin implements IMixinConfigPlugin {

    private static final Set<String> HEI_MIXINS = Set.of(
            "applygray.mixins.ae2.MixinRecipeTransferHandler",
            "applygray.mixins.ae2fc.MixinRecipeTransferBuilder",
            "applygray.mixins.ae2fc.MixinExtendedFluidPatternTerminalRecipeTransferHandler"
    );

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Nullable
    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return !HEI_MIXINS.contains(mixinClassName) || Loader.isModLoaded("jei");
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Nullable
    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName,
                         IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName,
                          IMixinInfo mixinInfo) {
    }
}
