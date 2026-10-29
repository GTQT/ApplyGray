package applygray.common.eco.gui;

import applygray.ApplyGrayMod;
import applygray.common.eco.*;
import gregtech.api.metatileentity.MetaTileEntity;
import gregtech.api.metatileentity.MetaTileEntityHolder;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.IGuiHandler;
import net.minecraftforge.fml.common.network.NetworkRegistry;

public final class EcoGuiHandler implements IGuiHandler {
    private static final int GUI_ID = 20;
    public static void initialize() { NetworkRegistry.INSTANCE.registerGuiHandler(ApplyGrayMod.instance, new EcoGuiHandler()); }
    public static void open(EntityPlayer player, MetaTileEntity host) {
        if (!player.world.isRemote) {
            var pos = host.getPos();
            player.openGui(ApplyGrayMod.instance, GUI_ID, host.getWorld(), pos.getX(), pos.getY(), pos.getZ());
        }
    }
    private static EcoContainer container(EntityPlayer player, World world, int x, int y, int z) {
        var tile = world.getTileEntity(new net.minecraft.util.math.BlockPos(x, y, z));
        if (!(tile instanceof MetaTileEntityHolder holder)) return null;
        var host = holder.getMetaTileEntity();
        if (!(host instanceof EcoController || host instanceof EcoPart || host instanceof EcoNetWork)) return null;
        return new EcoContainer(player.inventory, holder, host);
    }
    @Override public Object getServerGuiElement(int id, EntityPlayer player, World world, int x, int y, int z) {
        if (id != GUI_ID) return null;
        EcoContainer container = container(player, world, x, y, z);
        return container != null && container.canInteractWith(player) ? container : null;
    }
    @Override public Object getClientGuiElement(int id, EntityPlayer player, World world, int x, int y, int z) {
        if (id != GUI_ID) return null;
        EcoContainer container = container(player, world, x, y, z);
        return container == null ? null : ApplyGrayMod.proxy.createEcoScreen(container, player.inventory);
    }
}
