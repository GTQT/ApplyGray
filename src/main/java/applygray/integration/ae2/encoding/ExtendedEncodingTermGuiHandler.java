package applygray.integration.ae2.encoding;

import applygray.ApplyGrayMod;

import ae2.core.gui.PatternContainerGuiReturnContext;
import ae2.core.gui.locator.PartLocator;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.IGuiHandler;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import org.jetbrains.annotations.Nullable;

/**
 * 扩展样板编码终端的 GUI 入口。
 * <p>
 * 终端是挂在线上的 AE2 部件，坐标里塞不下朝向，所以把部件朝向编码进 {@code y} 的高 8 位；
 * 客户端屏幕由代理创建，避免服务端类直接引用 GUI。
 */
public final class ExtendedEncodingTermGuiHandler implements IGuiHandler {

    private static final int GUI_ID = 21;
    private static final int RETURNED_FROM_SUBSCREEN_FLAG = 1 << 30;
    public static final String STYLE = "/screens/terminals/applygray_extended_pattern_encoding_terminal.json";

    private ExtendedEncodingTermGuiHandler() {}

    public static void initialize() {
        NetworkRegistry.INSTANCE.registerGuiHandler(ApplyGrayMod.instance, new ExtendedEncodingTermGuiHandler());
    }

    public static int guiId(boolean returnedFromSubScreen) {
        return returnedFromSubScreen ? GUI_ID | RETURNED_FROM_SUBSCREEN_FLAG : GUI_ID;
    }

    @Override
    public @Nullable Object getServerGuiElement(int id, EntityPlayer player, World world, int x, int y, int z) {
        return createContainer(id, player, world, x, y, z);
    }

    @Override
    public @Nullable Object getClientGuiElement(int id, EntityPlayer player, World world, int x, int y, int z) {
        ContainerExtendedEncodingTerm container = createContainer(id, player, world, x, y, z);
        if (container == null) {
            return null;
        }
        return ApplyGrayMod.proxy.createExtendedEncodingTermScreen(container, player.inventory);
    }

    private static @Nullable ContainerExtendedEncodingTerm createContainer(int id, EntityPlayer player, World world,
                                                                          int x, int y, int z) {
        if (player == null || world == null || (id & ~RETURNED_FROM_SUBSCREEN_FLAG) != GUI_ID) {
            return null;
        }
        PartLocator locator = partLocator(x, y, z);
        if (locator == null) {
            return null;
        }
        ExtendedEncodingTermPart part = locator.locate(player, ExtendedEncodingTermPart.class);
        if (part == null) {
            return null;
        }
        ContainerExtendedEncodingTerm container = new ContainerExtendedEncodingTerm(player.inventory, part);
        container.setLocator(locator);
        container.setReturnedFromSubScreen((id & RETURNED_FROM_SUBSCREEN_FLAG) != 0);
        container.setGuiTitle(defaultGuiTitle(container.getTarget()));
        return PatternContainerGuiReturnContext.initializeContainer(container);
    }

    private static @Nullable PartLocator partLocator(int x, int y, int z) {
        int side = y >> 8;
        if (side == EnumFacing.VALUES.length) {
            return new PartLocator(new BlockPos(x, y & 255, z), null);
        }
        if (side < 0 || side > EnumFacing.VALUES.length) {
            return null;
        }
        return new PartLocator(new BlockPos(x, y & 255, z), EnumFacing.VALUES[side]);
    }

    private static @Nullable ITextComponent defaultGuiTitle(Object host) {
        if (host instanceof ExtendedEncodingTermPart part && part.hasCustomName()) {
            String customName = part.getCustomName();
            return customName == null || customName.isEmpty() ? null : new TextComponentString(customName);
        }
        return null;
    }
}
