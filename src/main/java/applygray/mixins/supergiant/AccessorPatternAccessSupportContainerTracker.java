package applygray.mixins.supergiant;

import ae2.helpers.patternprovider.PatternContainer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "ae2.container.me.patternaccess.PatternAccessSession$ContainerTracker", remap = false)
public interface AccessorPatternAccessSupportContainerTracker {

    @Accessor("container")
    PatternContainer applygray$getPatternContainer();
}
