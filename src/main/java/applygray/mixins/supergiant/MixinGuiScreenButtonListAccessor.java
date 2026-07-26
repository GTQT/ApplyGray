package applygray.mixins.supergiant;

import java.util.List;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(GuiScreen.class)
public interface MixinGuiScreenButtonListAccessor {

    @Accessor("buttonList")
    List<GuiButton> applygray$getButtonList();
}
