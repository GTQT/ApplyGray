package applygray.integration.theoneprobe;

import applygray.api.ApplyGrayAPI;
import applygray.common.metatileentities.multi.electric.eco.EcoTiers;
import applygray.common.metatileentities.multi.electric.eco.MetaTileEntityEcoComputer;
import applygray.common.metatileentities.multi.electric.eco.MetaTileEntityEcoController;
import applygray.common.metatileentities.multi.electric.eco.MetaTileEntityEcoEnergyStorage;
import applygray.common.metatileentities.multi.electric.eco.MetaTileEntityEcoMolecularAssembler;
import applygray.common.metatileentities.multi.electric.eco.MetaTileEntityEcoStorage;
import applygray.common.metatileentities.multi.multiblockpart.eco.EcoPartRole;
import applygray.common.metatileentities.multi.multiblockpart.eco.MetaTileEntityEcoDrivePart;
import applygray.common.metatileentities.multi.multiblockpart.eco.MetaTileEntityEcoEnergyPart;
import applygray.common.metatileentities.multi.multiblockpart.eco.MetaTileEntityEcoNetworkPart;
import applygray.common.metatileentities.multi.multiblockpart.eco.MetaTileEntityEcoPart;
import applygray.common.metatileentities.multi.multiblockpart.eco.MetaTileEntityEcoWorkerPart;

import gregtech.api.metatileentity.MetaTileEntity;
import gregtech.api.metatileentity.interfaces.IGregTechTileEntity;

import ae2.api.storage.cells.StorageCell;
import ae2.api.storage.cells.StorageCellStatistics;

import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import mcjty.theoneprobe.api.IProbeHitData;
import mcjty.theoneprobe.api.IProbeInfo;
import mcjty.theoneprobe.api.IProbeInfoProvider;
import mcjty.theoneprobe.api.ProbeMode;
import mcjty.theoneprobe.api.TextStyleClass;

/** ECO 机器的 TOP 摘要：只读控制器/仓室快照，不触发任何硬件重建。 */
public final class EcoInfoProvider implements IProbeInfoProvider {

    @Override
    public String getID() {
        return ApplyGrayAPI.MODID + ":eco";
    }

    @Override
    public void addProbeInfo(ProbeMode mode, IProbeInfo probeInfo, EntityPlayer player, World world,
                             IBlockState blockState, IProbeHitData data) {
        if (!blockState.getBlock().hasTileEntity(blockState)) return;
        TileEntity tile = world.getTileEntity(data.getPos());
        if (!(tile instanceof IGregTechTileEntity holder)) return;
        MetaTileEntity mte = holder.getMetaTileEntity();
        if (mte instanceof MetaTileEntityEcoController controller) addController(probeInfo, controller);
        else if (mte instanceof MetaTileEntityEcoPart part) addPart(probeInfo, part);
        else if (mte instanceof MetaTileEntityEcoNetworkPart port) addPort(probeInfo, port);
    }

    private static void addController(IProbeInfo probeInfo, MetaTileEntityEcoController controller) {
        probeInfo.text(TextStyleClass.INFO + controller.getStackForm().getDisplayName());
        probeInfo.text(label("applygray.top.eco.formed") + " " + info(controller.isStructureFormed()
                ? "{*applygray.top.eco.yes*}" : "{*applygray.top.eco.no*}"));
        probeInfo.text(label("applygray.top.eco.online") + " " + info(controller.online()
                ? "{*applygray.top.eco.yes*}" : "{*applygray.top.eco.no*}"));
        if (controller instanceof MetaTileEntityEcoComputer computer) {
            probeInfo.text(label("applygray.top.eco.memory") + " " + info(computer.memory().used() + " / "
                    + computer.memory().capacity() + " B"));
            probeInfo.text(label("applygray.top.eco.cpus") + " " + info(String.valueOf(computer.cpus().size())));
            probeInfo.text(label("applygray.top.eco.accelerators") + " " + info(String.valueOf(
                    computer.accelerators())));
        } else if (controller instanceof MetaTileEntityEcoStorage storage) {
            long used = 0;
            long total = 0;
            long types = 0;
            long maxTypes = 0;
            int cells = 0;
            for (MetaTileEntityEcoDrivePart drive : storage.parts(EcoPartRole.DRIVE,
                    MetaTileEntityEcoDrivePart.class)) {
                StorageCell cell = drive.cell();
                if (!(cell instanceof StorageCellStatistics stats)) continue;
                cells++;
                used += stats.getUsedBytes();
                total += stats.getTotalBytes();
                types += stats.getStoredTypes();
                maxTypes += stats.getTotalTypes();
            }
            probeInfo.text(label("applygray.top.eco.cells") + " " + info(String.valueOf(cells)));
            probeInfo.text(label("applygray.top.eco.storage") + " " + info(used + " / " + total + " B, "
                    + types + " / " + maxTypes + " types"));
        } else if (controller instanceof MetaTileEntityEcoMolecularAssembler assembler) {
            probeInfo.text(label("applygray.top.eco.workers") + " " + info(String.valueOf(
                    assembler.parts(EcoPartRole.WORKER, MetaTileEntityEcoWorkerPart.class).size())));
            probeInfo.text(label("applygray.top.eco.patterns") + " " + info(String.valueOf(
                    assembler.patterns().size())));
        } else if (controller instanceof MetaTileEntityEcoEnergyStorage battery) {
            probeInfo.text(label("applygray.top.eco.energy") + " " + info(
                    (long) battery.storedEnergy() + " / " + (long) battery.energyCapacity() + " AE"));
        }
    }

    private static void addPart(IProbeInfo probeInfo, MetaTileEntityEcoPart part) {
        probeInfo.text(TextStyleClass.INFO + part.getStackForm().getDisplayName());
        if (part instanceof MetaTileEntityEcoEnergyPart energy) {
            probeInfo.text(label("applygray.top.eco.energy") + " " + info(
                    (long) energy.storedEnergy() + " / " + EcoTiers.energy(energy.hardwareTier) + " AE"));
        } else if (part instanceof MetaTileEntityEcoWorkerPart worker) {
            probeInfo.text(label("applygray.top.eco.state") + " " + info("{*applygray.eco.state."
                    + worker.lane().state() + "*}"));
            probeInfo.text(label("applygray.top.eco.remaining") + " " + info(
                    String.valueOf(worker.lane().remaining())));
        } else if (part.role.slots > 0) {
            ItemStack stack = part.inventory().getStackInSlot(0);
            if (!stack.isEmpty()) {
                IProbeInfo row = probeInfo.horizontal();
                row.text(label("applygray.top.eco.installed"));
                row.item(stack);
                row.itemLabel(stack);
            }
        }
    }

    private static void addPort(IProbeInfo probeInfo, MetaTileEntityEcoNetworkPart port) {
        MetaTileEntityEcoController controller = port.controller();
        probeInfo.text(TextStyleClass.INFO + port.getStackForm().getDisplayName());
        probeInfo.text(label("applygray.top.eco.online") + " " + info(controller != null && controller.online()
                ? "{*applygray.top.eco.yes*}" : "{*applygray.top.eco.no*}"));
        if (controller != null) {
            probeInfo.text(label("applygray.top.eco.machine") + " "
                    + info(controller.getStackForm().getDisplayName()));
        }
    }

    private static String label(String key) {
        return TextStyleClass.LABEL + "{*" + key + "*}";
    }

    private static String info(String value) {
        return TextStyleClass.INFO + value;
    }
}
