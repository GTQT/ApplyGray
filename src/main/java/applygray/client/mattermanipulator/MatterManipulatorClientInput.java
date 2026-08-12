package applygray.client.mattermanipulator;

import applygray.mattermanipulator.item.ItemMatterManipulator;
import applygray.mattermanipulator.network.ConfigureManipulatorMessage;
import applygray.mattermanipulator.network.ExecuteManipulatorOperationMessage;
import applygray.mattermanipulator.network.MatterManipulatorNetwork;
import applygray.mattermanipulator.network.PickManipulatorBlockMessage;

import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumHand;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.client.event.MouseEvent;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.InputEvent;

import org.lwjgl.input.Keyboard;

/** Client-only key entry points for the server-authoritative Matter Manipulator workflow. */
public final class MatterManipulatorClientInput {

    private static final KeyBinding CONTROL = new KeyBinding("key.applygray.matter_manipulator.control",
            Keyboard.KEY_LCONTROL, "key.categories.applygray");
    private static final KeyBinding CUT = new KeyBinding("key.applygray.matter_manipulator.cut", Keyboard.KEY_X,
            "key.categories.applygray");
    private static final KeyBinding COPY = new KeyBinding("key.applygray.matter_manipulator.copy", Keyboard.KEY_C,
            "key.categories.applygray");
    private static final KeyBinding PASTE = new KeyBinding("key.applygray.matter_manipulator.paste", Keyboard.KEY_V,
            "key.categories.applygray");
    private static final KeyBinding RESET = new KeyBinding("key.applygray.matter_manipulator.reset", Keyboard.KEY_Z,
            "key.categories.applygray");
    private static boolean initialized;

    private MatterManipulatorClientInput() {}

    public static void initialize() {
        if (initialized) return;
        initialized = true;
        ClientRegistry.registerKeyBinding(CONTROL);
        ClientRegistry.registerKeyBinding(CUT);
        ClientRegistry.registerKeyBinding(COPY);
        ClientRegistry.registerKeyBinding(PASTE);
        ClientRegistry.registerKeyBinding(RESET);
        MinecraftForge.EVENT_BUS.register(MatterManipulatorClientInput.class);
    }

    @SubscribeEvent
    public static void onKeyInput(InputEvent.KeyInputEvent event) {
        Minecraft minecraft = Minecraft.getMinecraft();
        EnumHand hand = heldManipulator(minecraft.player);
        if (hand == null) return;

        if (minecraft.currentScreen != null || !CONTROL.isKeyDown()) return;
        if (CUT.isPressed()) send(hand, ConfigureManipulatorMessage.Action.MARK_CUT);
        if (COPY.isPressed()) send(hand, ConfigureManipulatorMessage.Action.MARK_COPY);
        if (PASTE.isPressed()) send(hand, ConfigureManipulatorMessage.Action.MARK_PASTE);
        if (RESET.isPressed()) send(hand, ConfigureManipulatorMessage.Action.RESET_SELECTIONS);
    }

    @SubscribeEvent
    public static void onMouseInput(MouseEvent event) {
        if (event.getButton() != 2 || !event.isButtonstate()) return;
        Minecraft minecraft = Minecraft.getMinecraft();
        EnumHand hand = heldManipulator(minecraft.player);
        if (hand == null || minecraft.currentScreen != null) return;
        MatterManipulatorNetwork.CHANNEL.sendToServer(new PickManipulatorBlockMessage(hand));
        event.setCanceled(true);
    }

    private static void send(EnumHand hand, ConfigureManipulatorMessage.Action action) {
        MatterManipulatorNetwork.CHANNEL.sendToServer(new ConfigureManipulatorMessage(hand, action));
    }

    private static EnumHand heldManipulator(EntityPlayer player) {
        if (player == null) return null;
        ItemStack mainHand = player.getHeldItemMainhand();
        if (mainHand.getItem() instanceof ItemMatterManipulator) return EnumHand.MAIN_HAND;
        ItemStack offHand = player.getHeldItemOffhand();
        return offHand.getItem() instanceof ItemMatterManipulator ? EnumHand.OFF_HAND : null;
    }
}
