package applygray.common.metatileentities.multi.electric.eco.gui;

import applygray.ApplyGrayMod;
import applygray.common.metatileentities.multi.electric.eco.MetaTileEntityEcoController;
import applygray.common.metatileentities.multi.multiblockpart.eco.MetaTileEntityEcoNetworkPart;
import applygray.common.metatileentities.multi.multiblockpart.eco.MetaTileEntityEcoPart;

import gregtech.api.metatileentity.MetaTileEntity;
import gregtech.api.metatileentity.MetaTileEntityHolder;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.IGuiHandler;
import net.minecraftforge.fml.common.network.NetworkRegistry;

/** ECO 自绘 UI 的唯一入口；控制器、仓室、ME 接口都走同一个容器。 */
public final class EcoGuiHandler implements IGuiHandler {

    private static final int GUI_ID = 20;

    public static void initialize() {
        NetworkRegistry.INSTANCE.registerGuiHandler(ApplyGrayMod.instance, new EcoGuiHandler());
    }

    public static void open(EntityPlayer player, MetaTileEntity host) {
        if (!player.world.isRemote) {
            BlockPos pos = host.getPos();
            player.openGui(ApplyGrayMod.instance, GUI_ID, host.getWorld(), pos.getX(), pos.getY(), pos.getZ());
        }
    }

    private static EcoContainer container(EntityPlayer player, World world, int x, int y, int z) {
        var tile = world.getTileEntity(new BlockPos(x, y, z));
        if (!(tile instanceof MetaTileEntityHolder holder)) return null;
        MetaTileEntity host = holder.getMetaTileEntity();
        if (!(host instanceof MetaTileEntityEcoController || host instanceof MetaTileEntityEcoPart
                || host instanceof MetaTileEntityEcoNetworkPart)) {
            return null;
        }
        return new EcoContainer(player.inventory, holder, host);
    }

    @Override
    public Object getServerGuiElement(int id, EntityPlayer player, World world, int x, int y, int z) {
        if (id != GUI_ID) return null;
        EcoContainer container = container(player, world, x, y, z);
        return container != null && container.canInteractWith(player) ? container : null;
    }

    @Override
    public Object getClientGuiElement(int id, EntityPlayer player, World world, int x, int y, int z) {
        if (id != GUI_ID) return null;
        EcoContainer container = container(player, world, x, y, z);
        return container == null ? null : ApplyGrayMod.proxy.createEcoScreen(container, player.inventory);
    }
}
