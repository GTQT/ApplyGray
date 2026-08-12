package applygray.common;

import net.minecraft.util.EnumHand;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;

/** Side-neutral lifecycle hook. Client-only Matter Manipulator input is installed by the client subclass. */
public class ApplyGrayCommonProxy {

    public void preInit(FMLPreInitializationEvent event) {}

    public void openMatterManipulatorConfiguration(EnumHand hand) {}
}
