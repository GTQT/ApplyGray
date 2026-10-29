package applygray.common.metatileentities.multi.multiblockpart.eco;

import gregtech.api.metatileentity.MetaTileEntity;
import gregtech.api.metatileentity.interfaces.IGregTechTileEntity;
import gregtech.client.renderer.ICubeRenderer;
import gregtech.client.renderer.texture.Textures;

import applygray.common.items.eco.EcoMemoryItem;

import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;

/** 计算内存仓：插 ECO 计算单元，容量由共享内存池统一结算。 */
public class MetaTileEntityEcoMemoryPart extends MetaTileEntityEcoPart {

    public MetaTileEntityEcoMemoryPart(ResourceLocation metaTileEntityId, int tier) {
        super(metaTileEntityId, EcoPartRole.MEMORY, tier);
    }

    @Override
    public MetaTileEntity createMetaTileEntity(IGregTechTileEntity holder) {
        return new MetaTileEntityEcoMemoryPart(metaTileEntityId, hardwareTier);
    }

    @Override
    protected boolean isItemValid(int slot, ItemStack stack) {
        int tier = EcoMemoryItem.tierOf(stack);
        return tier > 0 && tier <= hardwareTier;
    }

    @Override
    protected ICubeRenderer partOverlay() {
        return Textures.DATA_ACCESS_HATCH;
    }

    @Override
    protected void addTooltipLines(java.util.List<String> tooltip) {
        tooltip.add(applygray.common.metatileentities.multi.electric.eco.EcoTooltips.line(
                "applygray.eco.memory.tooltip.0"));
        tooltip.add(applygray.common.metatileentities.multi.electric.eco.EcoTooltips.line(
                "applygray.eco.memory.tooltip.1"));
        tooltip.add(applygray.common.metatileentities.multi.electric.eco.EcoTooltips.line(
                "applygray.eco.memory.tooltip.2"));
        tooltip.add(applygray.common.metatileentities.multi.electric.eco.EcoTooltips.line(
                "applygray.eco.memory.tooltip.3"));
        tooltip.add(applygray.common.metatileentities.multi.electric.eco.EcoTooltips.line(
                "applygray.eco.memory.tooltip.4"));
    }
}
