package applygray.mixins.supergiant;

import ae2.client.gui.AEBaseGui;
import ae2.container.AEBaseContainer;
import net.minecraft.client.gui.GuiButton;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.List;

/** Exposes the toolbar registration method declared on AE2's base GUI class. */
@Mixin(value = AEBaseGui.class, remap = false)
public interface InvokerAEBaseGui {

    @Invoker("addToLeftToolbar")
    <B extends GuiButton> B applygray$addToLeftToolbar(B button);

    @Invoker("drawTooltipLines")
    void applygray$drawTooltipLines(int mouseX, int mouseY, List<String> tooltip);

    @Invoker("getContainer")
    AEBaseContainer applygray$getContainer();

}
