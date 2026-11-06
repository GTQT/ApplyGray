package applygray.integration.jei;

import ae2.container.me.patternencode.ContainerPEATerm;
import ae2.container.me.patternencode.ContainerPatternEncodingTerm;
import applygray.integration.ae2.encoding.Util;

import mezz.jei.api.IModPlugin;
import mezz.jei.api.IModRegistry;
import mezz.jei.api.JEIPlugin;

import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * Registers the extended terminal transfer handler and the assembly line import pre-processing.
 */
@JEIPlugin
@SideOnly(Side.CLIENT)
public class ApplyGrayHeiPlugin implements IModPlugin {

    @Override
    public void register(IModRegistry registry) {
        registry.getRecipeTransferRegistry().addUniversalRecipeTransferHandler(
            new ExtendedPatternEncodingRecipeTransferHandler());

        // JEI prefers an exact recipe category handler over the universal handler AE2 registers for the standard
        // pattern encoding terminals, which lets assembly line auto naming run before AE2 plans the transfer.
        registry.getRecipeTransferRegistry().addRecipeTransferHandler(
            new HeiAssemblyLineRecipeTransferHandler<>(ContainerPatternEncodingTerm.class),
            Util.ASSEMBLY_LINE);
        registry.getRecipeTransferRegistry().addRecipeTransferHandler(
            new HeiAssemblyLineRecipeTransferHandler<>(ContainerPEATerm.class),
            Util.ASSEMBLY_LINE);
    }
}
