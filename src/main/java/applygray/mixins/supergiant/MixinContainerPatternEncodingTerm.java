package applygray.mixins.supergiant;

import ae2.container.me.patternencode.ContainerPatternEncodingTerm;
import applygray.integration.jei.AutoPatternItemNaming;
import applygray.integration.jei.ProcessingPatternCircuitImportMode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(value = ContainerPatternEncodingTerm.class, remap = false)
public abstract class MixinContainerPatternEncodingTerm
    implements ProcessingPatternCircuitImportMode, AutoPatternItemNaming {
    @Unique
    private boolean applygray$ignoreCircuit;

    @Unique
    private boolean applygray$autoNamePatternItems;

    @Override
    public boolean applygray$ignoreCircuit() {
        return this.applygray$ignoreCircuit;
    }

    @Override
    public void applygray$setIgnoreCircuit(boolean ignoreCircuit) {
        this.applygray$ignoreCircuit = ignoreCircuit;
    }

    @Override
    public boolean applygray$autoNamePatternItems() {
        return this.applygray$autoNamePatternItems;
    }

    @Override
    public void applygray$setAutoNamePatternItems(boolean autoName) {
        this.applygray$autoNamePatternItems = autoName;
    }
}
