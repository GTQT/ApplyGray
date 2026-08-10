package applygray.client.mattermanipulator;

import applygray.mattermanipulator.item.ItemMatterManipulator;
import applygray.mattermanipulator.network.ExecuteManipulatorOperationMessage;
import applygray.mattermanipulator.network.MatterManipulatorNetwork;

import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumHand;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.InputEvent;

import org.lwjgl.input.Keyboard;

/** Client-only key entry points for the server-authoritative Matter Manipulator workflow. */
public final class MatterManipulatorClientInput {

    private static final KeyBinding CONFIGURE = new KeyBinding("key.applygray.matter_manipulator.configure",
            Keyboard.KEY_M, "key.categories.applygray");
    private static final KeyBinding EXECUTE = new KeyBinding("key.applygray.matter_manipulator.execute", Keyboard.KEY_B,
            "key.categories.applygray");
    private static boolean initialized;

    private MatterManipulatorClientInput() {}

    public static void initialize() {
        if (initialized) return;
        initialized = true;
        ClientRegistry.registerKeyBinding(CONFIGURE);
        ClientRegistry.registerKeyBinding(EXECUTE);
        MinecraftForge.EVENT_BUS.register(MatterManipulatorClientInput.class);
    }

    @SubscribeEvent
    public static void onKeyInput(InputEvent.KeyInputEvent event) {
        Minecraft minecraft = Minecraft.getMinecraft();
        EnumHand hand = heldManipulator(minecraft.player);
        if (hand == null) return;

        if (CONFIGURE.isPressed() && minecraft.currentScreen == null) {
            minecraft.displayGuiScreen(new MatterManipulatorConfigScreen(hand));
        }
        if (EXECUTE.isPressed() && minecraft.currentScreen == null) {
            MatterManipulatorNetwork.CHANNEL.sendToServer(new ExecuteManipulatorOperationMessage(hand));
        }
    }

    private static EnumHand heldManipulator(EntityPlayer player) {
        if (player == null) return null;
        ItemStack mainHand = player.getHeldItemMainhand();
        if (mainHand.getItem() instanceof ItemMatterManipulator) return EnumHand.MAIN_HAND;
        ItemStack offHand = player.getHeldItemOffhand();
        return offHand.getItem() instanceof ItemMatterManipulator ? EnumHand.OFF_HAND : null;
    }
}
