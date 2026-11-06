package applygray.mixins.supergiant;

import ae2.container.me.patternencode.ContainerPatternEncodingTerm;
import applygray.integration.jei.PatternEncodingPanelContainerAccess;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(targets = "ae2.client.gui.me.patternencode.EncodingModePanel", remap = false)
public class MixinEncodingModePanel implements PatternEncodingPanelContainerAccess {

    @Shadow
    @Final
    protected ContainerPatternEncodingTerm container;

    @Override
    public ContainerPatternEncodingTerm applygray$getContainer() {
        return this.container;
    }
}
