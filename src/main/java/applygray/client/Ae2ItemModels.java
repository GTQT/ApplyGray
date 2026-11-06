package applygray.client;

import applygray.api.ApplyGrayAPI;
import applygray.common.items.ApplyGrayAeItems;
import applygray.common.items.ApplyGrayItems;
import ae2.api.util.AEColor;
import ae2.client.render.StaticItemColor;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.ModelResourceLocation;
import net.minecraft.item.Item;
import net.minecraftforge.client.event.ModelRegistryEvent;
import net.minecraftforge.client.model.ModelLoader;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;

/**
 * Client models and item colors for ApplyGray's standalone items.
 * <p>
 * Standalone {@link Item} instances register their model here by registry name; GT meta items keep using
 * {@code ApplyGrayMetaItemModels} and the {@code gregtech:metaitems/*} path convention.
 */
@Mod.EventBusSubscriber(modid = ApplyGrayAPI.MODID, value = Side.CLIENT)
public final class Ae2ItemModels {

    private Ae2ItemModels() {}

    @SubscribeEvent
    public static void register(ModelRegistryEvent event) {
        registerItem(ApplyGrayAeItems.CIRCUIT_CARD);
        registerItem(ApplyGrayAeItems.EXTENDED_ENCODING_TERM);
        registerItem(ApplyGrayItems.ORDER);
    }

    /**
     * Item colors are only available once Minecraft has finished its own init, which happens after
     * the model registry event, so AE2 registers its colors from the init phase as well.
     */
    public static void registerItemColors() {
        // 部件贴图是灰阶图层，颜色由 AE2 的静态调色板决定，未染色时保持透明。
        Minecraft.getMinecraft().getItemColors()
                .registerItemColorHandler(new StaticItemColor(AEColor.TRANSPARENT),
                        ApplyGrayAeItems.EXTENDED_ENCODING_TERM);
    }

    private static void registerItem(Item item) {
        ModelLoader.setCustomModelResourceLocation(item, 0,
                new ModelResourceLocation(item.getRegistryName(), "inventory"));
    }
}
