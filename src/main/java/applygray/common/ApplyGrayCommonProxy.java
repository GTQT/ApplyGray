package applygray.common;

import applygray.common.eco.gui.EcoContainer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.util.EnumHand;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;

/** Side-neutral lifecycle hook. Client-only Matter Manipulator input is installed by the client subclass. */
public class ApplyGrayCommonProxy {

    public Object createEcoScreen(EcoContainer container,
                                  InventoryPlayer inventory) { return null; }

    public void preInit(FMLPreInitializationEvent event) {}

    public void openMatterManipulatorConfiguration(EnumHand hand) {}
}
