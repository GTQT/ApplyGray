package applygray.client;

import applygray.client.gui.EcoGui;
import applygray.client.handler.HeiPatternAutoNamingHandler;
import applygray.client.handler.HeiPatternInputFilterHandler;
import applygray.client.gui.ae2.GuiExtendedPatternEncodingTerm;
import applygray.integration.ae2.encoding.ContainerExtendedEncodingTerm;
import applygray.integration.ae2.encoding.ExtendedEncodingTermGuiHandler;
import ae2.client.gui.style.GuiStyleManager;
import applygray.common.metatileentities.multiblock.eco.gui.EcoContainer;
import net.minecraft.entity.player.InventoryPlayer;
import applygray.client.mattermanipulator.MatterManipulatorClientInput;
import applygray.client.mattermanipulator.MatterManipulatorPreviewRenderer;
import applygray.common.ApplyGrayCommonProxy;
import applygray.mattermanipulator.network.MatterManipulatorNetwork;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.util.EnumHand;

/** Client lifecycle hook kept separate so a dedicated server never loads GUI or input classes. */
public final class ApplyGrayClientProxy extends ApplyGrayCommonProxy {

    @Override
    public Object createEcoScreen(EcoContainer container,
                                  InventoryPlayer inventory) {
        return new EcoGui(container, inventory);
    }

    @Override
    public Object createExtendedEncodingTermScreen(ContainerExtendedEncodingTerm container,
                                                   InventoryPlayer inventory) {
        return new GuiExtendedPatternEncodingTerm(container, inventory, container.getGuiTitle(),
            GuiStyleManager.loadStyleDoc(ExtendedEncodingTermGuiHandler.STYLE));
    }

    @Override
    public void preInit(FMLPreInitializationEvent event) {
        MatterManipulatorNetwork.initializeClient();
        MatterManipulatorClientInput.initialize();
        MatterManipulatorPreviewRenderer.initialize();
    }

    @Override
    public void init(net.minecraftforge.fml.common.event.FMLInitializationEvent event) {
        super.init(event);
        Ae2ItemModels.registerItemColors();
        // HEI pattern-transfer hooks only exist on the client, where recipe transfers start.
        MinecraftForge.EVENT_BUS.register(new HeiPatternAutoNamingHandler());
        MinecraftForge.EVENT_BUS.register(new HeiPatternInputFilterHandler());
    }

    @Override
    public void openOrderNameScreen(net.minecraft.item.ItemStack stack, EnumHand hand) {
        Minecraft.getMinecraft().displayGuiScreen(new applygray.client.gui.OrderNameScreen(stack, hand));
    }

    @Override
    public void openMatterManipulatorConfiguration(EnumHand hand) {
        Minecraft.getMinecraft().displayGuiScreen(new applygray.client.mattermanipulator.MatterManipulatorConfigScreen(hand));
    }
}
