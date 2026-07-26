package applygray.mixins.supergiant;

import ae2.client.gui.AEBaseGui;
import net.minecraft.client.gui.GuiButton;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Exposes the toolbar registration method declared on AE2's base GUI class. */
@Mixin(value = AEBaseGui.class, remap = false)
public interface InvokerAEBaseGui {

    @Invoker("addToLeftToolbar")
    <B extends GuiButton> B applygray$addToLeftToolbar(B button);
}
