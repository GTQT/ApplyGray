package applygray.mattermanipulator.network;

import applygray.api.ApplyGrayAPI;
import applygray.mattermanipulator.state.ManipulatorState;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.EnumHand;

import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import net.minecraftforge.fml.relauncher.Side;

/** Bounded intent-only packet channel for Matter Manipulator client interactions. */
public final class MatterManipulatorNetwork {

    public static final SimpleNetworkWrapper CHANNEL = NetworkRegistry.INSTANCE.newSimpleChannel("applygray_mm");
    private static boolean initialized;

    private MatterManipulatorNetwork() {}

    public static void initialize() {
        if (initialized) return;
        initialized = true;
        CHANNEL.registerMessage(ExecuteManipulatorOperationMessage.Handler.class, ExecuteManipulatorOperationMessage.class,
                0, Side.SERVER);
        CHANNEL.registerMessage(ConfigureManipulatorMessage.Handler.class, ConfigureManipulatorMessage.class, 1,
                Side.SERVER);
        CHANNEL.registerMessage(PickManipulatorBlockMessage.Handler.class, PickManipulatorBlockMessage.class, 3,
                Side.SERVER);
    }

    /** Registered only through the client proxy so dedicated servers never resolve client GUI classes. */
    public static void initializeClient() {
        CHANNEL.registerMessage(applygray.client.mattermanipulator.ManipulatorStateSyncHandler.class,
                ManipulatorStateSyncMessage.class, 2, Side.CLIENT);
    }

    public static void sendStateTo(EntityPlayerMP player, EnumHand hand, ManipulatorState state) {
        CHANNEL.sendTo(new ManipulatorStateSyncMessage(hand, state), player);
    }
}
