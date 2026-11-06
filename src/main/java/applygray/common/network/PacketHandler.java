package applygray.common.network;

import applygray.common.network.packet.SetOrderNameMessage;

import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import net.minecraftforge.fml.relauncher.Side;

/**
 * 通用网络通道：只做注册与发送入口。
 * <p>
 * 消息体在 {@code applygray.common.network.packet} 下各自实现 {@code IMessage}，
 * 并在这里显式登记，避免发送端按类名反射查找通道。
 */
public final class PacketHandler {

    public static final SimpleNetworkWrapper CHANNEL = NetworkRegistry.INSTANCE.newSimpleChannel("applygray");

    private static boolean initialized;

    private PacketHandler() {}

    public static void initialize() {
        if (initialized) return;
        initialized = true;
        CHANNEL.registerMessage(SetOrderNameMessage.Handler.class, SetOrderNameMessage.class, 0, Side.SERVER);
    }
}
