package applygray.common.network.packet;

import applygray.common.items.ApplyGrayOrderItem;

import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumHand;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * 客户端改订单名称。服务端只接受手持订单物品的玩家，并忽略超出长度上限的内容。
 */
public final class SetOrderNameMessage implements IMessage {

    private EnumHand hand;
    private String name;

    public SetOrderNameMessage() {}

    public SetOrderNameMessage(EnumHand hand, String name) {
        this.hand = hand;
        this.name = name;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        hand = EnumHand.values()[buf.readByte()];
        name = ByteBufUtils.readUTF8String(buf);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeByte(hand.ordinal());
        ByteBufUtils.writeUTF8String(buf, name == null ? "" : name);
    }

    public static final class Handler implements IMessageHandler<SetOrderNameMessage, IMessage> {

        @Override
        public IMessage onMessage(SetOrderNameMessage message, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            player.getServerWorld().addScheduledTask(() -> {
                ItemStack held = player.getHeldItem(message.hand);
                if (!(held.getItem() instanceof ApplyGrayOrderItem)) return;
                ApplyGrayOrderItem.setOrderName(held, message.name);
                player.inventoryContainer.detectAndSendChanges();
            });
            return null;
        }
    }
}
