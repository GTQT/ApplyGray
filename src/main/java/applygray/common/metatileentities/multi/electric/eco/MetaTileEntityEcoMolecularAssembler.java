package applygray.common.metatileentities.multi.electric.eco;

import gregtech.api.metatileentity.MetaTileEntity;
import gregtech.api.metatileentity.interfaces.IGregTechTileEntity;
import gregtech.api.pattern.element.StructureDefinition;

import applygray.common.metatileentities.multi.multiblockpart.eco.EcoPartRole;
import applygray.common.metatileentities.multi.multiblockpart.eco.MetaTileEntityEcoPatternBusPart;
import applygray.common.metatileentities.multi.multiblockpart.eco.MetaTileEntityEcoWorkerPart;

import ae2.api.crafting.IAssemblerPattern;
import ae2.api.crafting.IPatternDetails;
import ae2.api.crafting.PatternDetailsHelper;
import ae2.api.stacks.AEKey;

import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;

import org.jspecify.annotations.NonNull;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 多方块分子装配室：样板总线只收合成样板，Worker 仓按批次执行。 */
public class MetaTileEntityEcoMolecularAssembler extends MetaTileEntityEcoController {

    private static final StructureDefinition<?> STRUCTURE_DEFINITION = EcoStructures.assembler();

    private final Map<AEKey, IAssemblerPattern> patternIndex = new LinkedHashMap<>();
    private List<IAssemblerPattern> patterns = List.of();
    private int nextLane;

    public MetaTileEntityEcoMolecularAssembler(ResourceLocation metaTileEntityId) {
        super(metaTileEntityId, EcoControllerType.ASSEMBLER);
    }

    @Override
    public MetaTileEntity createMetaTileEntity(IGregTechTileEntity holder) {
        return new MetaTileEntityEcoMolecularAssembler(metaTileEntityId);
    }

    @Override
    protected @NonNull StructureDefinition<?> createStructureDefinition() {
        return STRUCTURE_DEFINITION;
    }

    @Override
    @net.minecraftforge.fml.relauncher.SideOnly(net.minecraftforge.fml.relauncher.Side.CLIENT)
    protected void addTooltipLines(java.util.List<String> tooltip) {
        tooltip.add(EcoTooltips.line("applygray.eco.assembler.tooltip.0"));
        tooltip.add(EcoTooltips.line("applygray.eco.assembler.tooltip.1", EcoTiers.MAX_SEGMENTS));
        tooltip.add(EcoTooltips.line("applygray.eco.assembler.tooltip.2"));
        tooltip.add(EcoTooltips.line("applygray.eco.assembler.tooltip.3"));
        tooltip.add(EcoTooltips.line("applygray.eco.assembler.tooltip.4"));
        tooltip.add(EcoTooltips.line("applygray.eco.assembler.tooltip.5"));
    }

    public List<IAssemblerPattern> patterns() {
        return patterns;
    }

    public boolean hasPattern(IPatternDetails details) {
        return patternIndex.containsKey(details.getDefinition());
    }

    public MetaTileEntityEcoWorkerPart freeLane(int multiplier) {
        var workers = parts(EcoPartRole.WORKER, MetaTileEntityEcoWorkerPart.class);
        for (int i = 0; i < workers.size(); i++) {
            int index = (nextLane + i) % workers.size();
            MetaTileEntityEcoWorkerPart worker = workers.get(index);
            if (worker.lane().canAccept() && EcoConfig.batch(worker.hardwareTier) >= multiplier) {
                nextLane = (index + 1) % workers.size();
                return worker;
            }
        }
        return null;
    }

    @Override
    protected void rebuildParts() {
        patternIndex.clear();
        for (MetaTileEntityEcoPatternBusPart bus : parts(EcoPartRole.PATTERN_BUS,
                MetaTileEntityEcoPatternBusPart.class)) {
            for (int slot = 0; slot < bus.inventory().getSlots(); slot++) {
                ItemStack stack = bus.inventory().getStackInSlot(slot);
                IPatternDetails details = PatternDetailsHelper.decodePattern(stack, getWorld());
                if (details instanceof IAssemblerPattern pattern) {
                    patternIndex.putIfAbsent(details.getDefinition(), pattern);
                }
            }
        }
        patterns = List.copyOf(patternIndex.values());
    }

    @Override
    protected void onOnlineTick() {
        for (MetaTileEntityEcoWorkerPart worker : parts(EcoPartRole.WORKER, MetaTileEntityEcoWorkerPart.class)) {
            worker.lane().tick(this);
        }
    }
}
