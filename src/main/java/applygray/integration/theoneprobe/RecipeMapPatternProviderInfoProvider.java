package applygray.integration.theoneprobe;

import applygray.api.ApplyGrayAPI;

import gregtech.api.metatileentity.MetaTileEntity;
import gregtech.api.metatileentity.interfaces.IGregTechTileEntity;
import gregtech.api.util.TextFormattingUtil;
import applygray.integration.ae2.recipe.RecipeBinding;
import gregtech.common.metatileentities.multi.multiblockpart.appeng.MetaTileEntityMEPatternProvider.PatternBuffer;
import gregtech.common.metatileentities.multi.multiblockpart.appeng.MetaTileEntityMERecipeMapPatternProvider;

import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;
import net.minecraftforge.fluids.FluidStack;

import mcjty.theoneprobe.api.IProbeHitData;
import mcjty.theoneprobe.api.IProbeInfo;
import mcjty.theoneprobe.api.IProbeInfoProvider;
import mcjty.theoneprobe.api.ProbeMode;
import mcjty.theoneprobe.api.TextStyleClass;

/** Displays the real material inputs queued in a dynamic RecipeMap pattern provider. */
public final class RecipeMapPatternProviderInfoProvider implements IProbeInfoProvider {

    private static final int NORMAL_ENTRY_LIMIT = 8;
    private static final int EXTENDED_ENTRY_LIMIT = 32;

    @Override
    public String getID() {
        return ApplyGrayAPI.MODID + ":recipe_map_pattern_provider_contents";
    }

    @Override
    public void addProbeInfo(ProbeMode mode, IProbeInfo probeInfo, EntityPlayer player, World world,
                             IBlockState blockState, IProbeHitData data) {
        if (!blockState.getBlock().hasTileEntity(blockState)) return;

        TileEntity tileEntity = world.getTileEntity(data.getPos());
        if (!(tileEntity instanceof IGregTechTileEntity gregTechTile)) return;
        MetaTileEntity metaTileEntity = gregTechTile.getMetaTileEntity();
        if (!(metaTileEntity instanceof MetaTileEntityMERecipeMapPatternProvider provider)) return;

        int usedBuffers = countUsedBuffers(provider);
        if (usedBuffers == 0) {
            probeInfo.text(TextStyleClass.INFO + "{*applygray.top.recipe_map_pattern_provider.empty*}");
            return;
        }

        probeInfo.text(TextStyleClass.INFO + "{*applygray.top.recipe_map_pattern_provider.contents*}: " +
                TextFormatting.AQUA + usedBuffers + "/" + provider.getBufferCount());

        int entryLimit = entryLimit(mode);
        int shownEntries = 0;
        int hiddenEntries = 0;
        for (int bufferIndex = 0; bufferIndex < provider.getBufferPool().size(); bufferIndex++) {
            PatternBuffer buffer = provider.getBufferPool().get(bufferIndex);
            addBindingEntry(probeInfo, bufferIndex, buffer.getRecipeBinding());
            for (int slot = 0; slot < buffer.getItemHandler().getSlots(); slot++) {
                ItemStack stack = buffer.getItemHandler().getStackInSlot(slot);
                if (stack.isEmpty()) continue;

                if (shownEntries++ < entryLimit) {
                    addItemEntry(probeInfo, bufferIndex, stack);
                } else {
                    hiddenEntries++;
                }
            }
            for (int slot = 0; slot < buffer.getCircuitSlot().getSlots(); slot++) {
                ItemStack circuit = buffer.getCircuitSlot().getStackInSlot(slot);
                if (circuit.isEmpty()) continue;

                if (shownEntries++ < entryLimit) {
                    addCircuitEntry(probeInfo, bufferIndex, circuit);
                } else {
                    hiddenEntries++;
                }
            }
            for (int tank = 0; tank < buffer.getFluidHandler().getTanks(); tank++) {
                FluidStack fluid = buffer.getFluidHandler().getTankAt(tank).getFluid();
                if (fluid == null || fluid.amount <= 0) continue;

                if (shownEntries++ < entryLimit) {
                    addFluidEntry(probeInfo, bufferIndex, fluid);
                } else {
                    hiddenEntries++;
                }
            }
        }

        if (hiddenEntries > 0) {
            probeInfo.text(TextStyleClass.LABEL + "... " + TextFormatting.AQUA + hiddenEntries + " " +
                    TextStyleClass.INFO + "{*applygray.top.recipe_map_pattern_provider.more*}");
        }
    }

    private static int countUsedBuffers(MetaTileEntityMERecipeMapPatternProvider provider) {
        int usedBuffers = 0;
        for (PatternBuffer buffer : provider.getBufferPool()) {
            if (!buffer.isItemAndFluidEmpty()) usedBuffers++;
        }
        return usedBuffers;
    }

    private static int entryLimit(ProbeMode mode) {
        return switch (mode) {
            case NORMAL -> NORMAL_ENTRY_LIMIT;
            case EXTENDED -> EXTENDED_ENTRY_LIMIT;
            case DEBUG -> Integer.MAX_VALUE;
        };
    }

    private static void addItemEntry(IProbeInfo probeInfo, int bufferIndex, ItemStack stack) {
        IProbeInfo row = probeInfo.horizontal();
        row.text(TextStyleClass.LABEL + "#" + (bufferIndex + 1));
        row.item(stack);
        row.itemLabel(stack);
        row.text(TextStyleClass.INFO + " x" + TextFormatting.AQUA +
                TextFormattingUtil.formatNumbers(stack.getCount()));
    }

    private static void addBindingEntry(IProbeInfo probeInfo, int bufferIndex, RecipeBinding binding) {
        if (binding == null) return;
        probeInfo.text(TextStyleClass.LABEL + "#" + (bufferIndex + 1) + " " + TextFormatting.DARK_AQUA +
                binding.describe());
    }

    private static void addCircuitEntry(IProbeInfo probeInfo, int bufferIndex, ItemStack circuit) {
        IProbeInfo row = probeInfo.horizontal();
        row.text(TextStyleClass.LABEL + "#" + (bufferIndex + 1) + " " + TextFormatting.DARK_PURPLE + "Circuit");
        row.item(circuit);
        row.itemLabel(circuit);
    }

    private static void addFluidEntry(IProbeInfo probeInfo, int bufferIndex, FluidStack fluid) {
        probeInfo.text(TextStyleClass.LABEL + "#" + (bufferIndex + 1) + " " + TextStyleClass.INFO +
                fluid.getLocalizedName() + " " + TextFormatting.AQUA +
                TextFormattingUtil.formatNumbers(fluid.amount) + " mB");
    }
}
