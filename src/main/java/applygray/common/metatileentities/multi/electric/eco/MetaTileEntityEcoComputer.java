package applygray.common.metatileentities.multi.electric.eco;

import gregtech.api.metatileentity.MetaTileEntity;
import gregtech.api.metatileentity.interfaces.IGregTechTileEntity;
import gregtech.api.pattern.element.StructureDefinition;
import gregtech.client.renderer.ICubeRenderer;
import gregtech.client.renderer.texture.Textures;

import applygray.common.items.eco.EcoMemoryItem;
import applygray.common.metatileentities.multi.electric.eco.logic.EcoCpu;
import applygray.common.metatileentities.multi.electric.eco.logic.EcoMemoryPool;
import applygray.common.metatileentities.multi.multiblockpart.eco.EcoPartRole;
import applygray.common.metatileentities.multi.multiblockpart.eco.MetaTileEntityEcoPart;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.ResourceLocation;

import org.jspecify.annotations.NonNull;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 多方块 CPU：共享内存池、线程/超线程/并行核，以及 AE2 原生合成任务。 */
public class MetaTileEntityEcoComputer extends MetaTileEntityEcoController {

    private static final StructureDefinition<?> STRUCTURE_DEFINITION = EcoStructures.computer();

    private final EcoMemoryPool memory = new EcoMemoryPool();
    private final Map<String, EcoCpu> cpus = new LinkedHashMap<>();
    private List<EcoCpu> cpuSnapshot = List.of();
    private int accelerators;
    private NBTTagList pendingCpus;

    public MetaTileEntityEcoComputer(ResourceLocation metaTileEntityId) {
        super(metaTileEntityId, EcoControllerType.COMPUTER);
    }

    @Override
    public MetaTileEntity createMetaTileEntity(IGregTechTileEntity holder) {
        return new MetaTileEntityEcoComputer(metaTileEntityId);
    }

    @Override
    protected @NonNull StructureDefinition<?> createStructureDefinition() {
        return STRUCTURE_DEFINITION;
    }

    @Override
    @net.minecraftforge.fml.relauncher.SideOnly(net.minecraftforge.fml.relauncher.Side.CLIENT)
    protected void addTooltipLines(java.util.List<String> tooltip) {
        tooltip.add(EcoTooltips.line("applygray.eco.computer.tooltip.0"));
        tooltip.add(EcoTooltips.line("applygray.eco.computer.tooltip.1", EcoTiers.MAX_SEGMENTS));
        tooltip.add(EcoTooltips.line("applygray.eco.computer.tooltip.2"));
        tooltip.add(EcoTooltips.line("applygray.eco.computer.tooltip.3"));
        tooltip.add(EcoTooltips.line("applygray.eco.computer.tooltip.4"));
        tooltip.add(EcoTooltips.line("applygray.eco.computer.tooltip.5"));
        tooltip.add(EcoTooltips.line("applygray.eco.computer.tooltip.6"));
    }

    public EcoMemoryPool memory() {
        return memory;
    }

    public List<EcoCpu> cpus() {
        return cpuSnapshot;
    }

    public int cpuOrdinal(EcoCpu cpu) {
        return cpuSnapshot.indexOf(cpu);
    }

    public int accelerators() {
        return accelerators;
    }

    @Override
    protected void rebuildParts() {
        restoreCpus();
        long bytes = 0;
        for (MetaTileEntityEcoPart part : parts(EcoPartRole.MEMORY)) {
            int tier = EcoMemoryItem.tierOf(part.inventory().getStackInSlot(0));
            if (tier > 0) bytes = Math.addExact(bytes, EcoTiers.memory(tier));
        }
        memory.setCapacity(bytes);
        accelerators = parts(EcoPartRole.PARALLEL).stream()
                .mapToInt(part -> EcoTiers.accelerators(part.hardwareTier))
                .sum();
        cpus.values().forEach(cpu -> cpu.installed = false);
        for (MetaTileEntityEcoPart part : parts(EcoPartRole.THREAD)) {
            installCpus(part, false, EcoTiers.threads(part.hardwareTier, false));
        }
        for (MetaTileEntityEcoPart part : parts(EcoPartRole.HYPER_THREAD)) {
            installCpus(part, false, EcoTiers.normalThreadsOnHyperCore(part.hardwareTier));
            installCpus(part, true, EcoTiers.threads(part.hardwareTier, true));
        }
        // Retain retired CPU buffers until AE2 has returned their contents; never recycle active identities.
        for (EcoCpu cpu : cpus.values()) {
            if (!cpu.installed && cpu.isBusy()) cpu.cancelJob();
        }
        cpus.entrySet().removeIf(entry -> {
            EcoCpu cpu = entry.getValue();
            if (cpu.installed || cpu.isBusy()) return false;
            memory.release(cpu.id);
            cpu.destroy();
            return true;
        });
        cpuSnapshot = List.copyOf(cpus.values());
    }

    @Override
    protected void onOnlineTick() {
        for (EcoCpu cpu : cpuSnapshot) cpu.reconcileReservation();
        if (cpuSnapshot.stream().anyMatch(cpu -> !cpu.installed && !cpu.isBusy())) hardwareChanged();
    }

    private void restoreCpus() {
        if (pendingCpus == null || getWorld() == null) return;
        for (int i = 0; i < pendingCpus.tagCount(); i++) {
            NBTTagCompound tag = pendingCpus.getCompoundTagAt(i);
            EcoCpu cpu = new EcoCpu(this, tag.getUniqueId("id"), tag.getString("slot"), tag.getBoolean("hyper"));
            cpu.readFromNBT(tag.getCompoundTag("native"));
            cpus.put(cpu.slot, cpu);
            long reserved = tag.getLong("reserved");
            if (reserved > 0) memory.restore(cpu.id, reserved);
        }
        pendingCpus = null;
    }

    private void installCpus(MetaTileEntityEcoPart part, boolean hyper, int count) {
        for (int i = 0; i < count; i++) {
            String key = part.getPos().toLong() + "/" + (hyper ? "h" : "n") + i;
            EcoCpu cpu = cpus.computeIfAbsent(key, ignored -> new EcoCpu(this, UUID.randomUUID(), key, hyper));
            cpu.installed = true;
        }
    }

    @Override
    protected void saveExtra(NBTTagCompound tag) {
        NBTTagList list = new NBTTagList();
        if (pendingCpus != null) list = pendingCpus.copy();
        else for (EcoCpu cpu : cpus.values()) list.appendTag(cpu.save());
        tag.setTag("EcoCpus", list);
    }

    @Override
    protected void loadExtra(NBTTagCompound tag) {
        pendingCpus = tag.getTagList("EcoCpus", 10).copy();
    }
}
