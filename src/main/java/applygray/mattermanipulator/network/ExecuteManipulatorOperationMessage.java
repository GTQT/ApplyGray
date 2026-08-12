package applygray.mattermanipulator.network;

import applygray.mattermanipulator.server.MatterManipulatorBuildManager;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.EnumHand;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import io.netty.buffer.ByteBuf;

/** Client request to start or cancel the currently configured server-authoritative manipulator operation. */
public final class ExecuteManipulatorOperationMessage implements IMessage {

    private EnumHand hand;
    private Action action;

    public ExecuteManipulatorOperationMessage() {}

    public ExecuteManipulatorOperationMessage(EnumHand hand, Action action) {
        this.hand = hand;
        this.action = action;
    }

    @Override
    public void fromBytes(ByteBuf buffer) {
        hand = buffer.readBoolean() ? EnumHand.MAIN_HAND : EnumHand.OFF_HAND;
        action = buffer.readBoolean() ? Action.START : Action.STOP;
    }

    @Override
    public void toBytes(ByteBuf buffer) {
        buffer.writeBoolean(hand == EnumHand.MAIN_HAND);
        buffer.writeBoolean(action == Action.START);
    }

    public static final class Handler implements IMessageHandler<ExecuteManipulatorOperationMessage, IMessage> {

        @Override
        public IMessage onMessage(ExecuteManipulatorOperationMessage message, MessageContext context) {
            EntityPlayerMP player = context.getServerHandler().player;
            player.getServerWorld().addScheduledTask(() -> {
                if (message.action == Action.START) MatterManipulatorBuildManager.start(player, message.hand);
                else MatterManipulatorBuildManager.stop(player, message.hand);
            });
            return null;
        }
    }

    public enum Action { START, STOP }
}
