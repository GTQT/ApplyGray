package applygray.client;

import applygray.client.mattermanipulator.MatterManipulatorClientInput;
import applygray.client.mattermanipulator.MatterManipulatorPreviewRenderer;
import applygray.common.ApplyGrayCommonProxy;
import applygray.mattermanipulator.network.MatterManipulatorNetwork;

import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;

/** Client lifecycle hook kept separate so a dedicated server never loads GUI or input classes. */
public final class ApplyGrayClientProxy extends ApplyGrayCommonProxy {

    @Override
    public void preInit(FMLPreInitializationEvent event) {
        MatterManipulatorNetwork.initializeClient();
        MatterManipulatorClientInput.initialize();
        MatterManipulatorPreviewRenderer.initialize();
    }
}
