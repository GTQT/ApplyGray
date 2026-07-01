package applygray.integration;

import applygray.ApplyGrayMod;
import applygray.client.renderer.texture.ApplyGrayTextures;

import gregtech.api.color.ColoredBlockContainer;
import gregtech.api.color.containers.AE2ColorContainer;
import gregtech.api.metatileentity.MachineBlockHighlighter;
import gregtech.api.pattern.StructureItemSourceRegistry;
import gregtech.api.util.GTUtility;
import gregtech.client.utils.ItemRenderCompat;
import gregtech.common.items.tool.rotation.AECustomBlockRotations;
import gregtech.integration.ae2.GTCircuitHelper;

import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import appeng.client.render.BlockPosHighlighter;
import appeng.integration.modules.gregtech.CircuitHelper;
import appeng.util.BlockPosUtils;

public final class ApplyGrayIntegrationBootstrap {

    private ApplyGrayIntegrationBootstrap() {}

    public static void init() {
        CircuitHelper.setInstance(new GTCircuitHelper());
        StructureItemSourceRegistry.register(new AE2StructureItemSource());
        ColoredBlockContainer.registerContainer(new AE2ColorContainer(GTUtility.gregtechId("ae2")));
        AECustomBlockRotations.init();
        if (FMLCommonHandler.instance().getSide() == Side.CLIENT) {
            ClientBootstrap.init();
        }
        ApplyGrayMod.LOGGER.info("Enabled GregTech AE2 integration hooks");
    }

    @SideOnly(Side.CLIENT)
    private static final class ClientBootstrap {

        private static void init() {
            ApplyGrayTextures.init();
            ItemRenderCompat.registerExtractor(new AE2RepresentativeStackExtractor());
            MachineBlockHighlighter.setHandler((player, pos) -> {
                double distance = BlockPosUtils.getDistance(pos, player.getPosition());
                long until = (long) (System.currentTimeMillis() + 500 * distance);
                BlockPosHighlighter.hilightBlock(pos, until, player.world.provider.getDimension());
            });
        }
    }
}
