package applygray.common;

import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.util.EnumHand;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;

import applygray.common.metatileentities.multiblock.eco.gui.EcoContainer;
import applygray.integration.ae2.encoding.ContainerExtendedEncodingTerm;

/** Side-neutral lifecycle hook. Client-only Matter Manipulator input is installed by the client subclass. */
public class ApplyGrayCommonProxy {

    public Object createEcoScreen(EcoContainer container,
                                  InventoryPlayer inventory) {return null;}

    /** Client-only screen factory; returns null on the server side. */
    public Object createExtendedEncodingTermScreen(ContainerExtendedEncodingTerm container,
                                                   InventoryPlayer inventory) {return null;}

    public void preInit(FMLPreInitializationEvent event) {}

    public void init(FMLInitializationEvent event) {}

    public void openMatterManipulatorConfiguration(EnumHand hand) {}

    /** Client-only order renaming screen; the server side never draws it. */
    public void openOrderNameScreen(net.minecraft.item.ItemStack stack, EnumHand hand) {}
}
