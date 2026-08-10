package applygray.client.mattermanipulator;

import applygray.mattermanipulator.item.ItemMatterManipulator;
import applygray.mattermanipulator.network.ManipulatorStateSyncMessage;

import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/** Client-only application of a state change that the server has already validated. */
public final class ManipulatorStateSyncHandler implements IMessageHandler<ManipulatorStateSyncMessage, IMessage> {

    @Override
    public IMessage onMessage(ManipulatorStateSyncMessage message, MessageContext context) {
        Minecraft.getMinecraft().addScheduledTask(() -> {
            if (Minecraft.getMinecraft().player == null) return;
            ItemStack stack = Minecraft.getMinecraft().player.getHeldItem(message.hand());
            if (stack.getItem() instanceof ItemMatterManipulator manipulator) {
                manipulator.saveState(stack, message.state());
            }
        });
        return null;
    }
}
