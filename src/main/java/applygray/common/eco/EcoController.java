package applygray.common.eco;

import gregtech.api.util.RelativeDirection;
import net.minecraft.client.resources.I18n;
import net.minecraft.network.PacketBuffer;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import ae2.api.crafting.IAssemblerPattern;
import ae2.api.crafting.IPatternDetails;
import ae2.api.crafting.PatternDetailsHelper;
import ae2.api.networking.events.GridCraftingCpuChange;
import ae2.api.stacks.AEItemKey;
import ae2.api.storage.cells.StorageCell;
import applygray.common.eco.gui.EcoGuiHandler;
import codechicken.lib.raytracer.CuboidRayTraceResult;
import gregtech.api.metatileentity.MetaTileEntity;
import gregtech.api.metatileentity.interfaces.IGregTechTileEntity;
import gregtech.api.metatileentity.multiblock.IMultiblockPart;
import gregtech.api.metatileentity.multiblock.MultiblockWithDisplayBase;
import gregtech.api.pattern.FormedStructureView;
import gregtech.api.pattern.casing.DeclarativePatternBuilder;
import gregtech.api.pattern.casing.GTStructureChannels;
import gregtech.api.pattern.element.StructureDefinition;
import gregtech.client.renderer.ICubeRenderer;
import gregtech.client.renderer.texture.Textures;
import gregtech.common.blocks.BlockComputerCasing;
import gregtech.common.blocks.BlockGlassCasing;
import gregtech.common.blocks.MetaBlocks;
import gregtech.api.unification.material.Materials;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.ResourceLocation;
import java.util.*;
import static gregtech.api.pattern.element.Elements.*;

/** PrismPlan's expandable computer/storage racks, plus the new molecular assembly hall. */
public final class EcoController extends MultiblockWithDisplayBase {
    public enum Kind {
        COMPUTER("computer"), STORAGE("storage"), ASSEMBLER("molecular_assembler");
        public final String id;
        Kind(String id) { this.id = id; }
    }
    public final Kind kind;
    public final EcoMemoryPool memory = new EcoMemoryPool();
    private UUID identity = UUID.randomUUID();
    private final Map<String, EcoCpu> cpus = new LinkedHashMap<>();
    private final Map<AEItemKey, IAssemblerPattern> patternIndex = new LinkedHashMap<>();
    private List<IAssemblerPattern> patterns = List.of();
    private List<EcoCpu> cpuSnapshot = List.of();
    private EcoNetWork port;
    private boolean dirty = true;
    private boolean enabled = true;
    private int accelerators;
    private int nextLane;
    private NBTTagList pendingCpus;
    private boolean visualActive;
    private static final int STATUS_DATA = 0x45434F;

    public EcoController(ResourceLocation id, Kind kind) { super(id); this.kind = kind; }
    @Override public MetaTileEntity createMetaTileEntity(IGregTechTileEntity holder) {
        return new EcoController(metaTileEntityId, kind);
    }
    public UUID identity() { return identity; }
    public EcoNetWork port() { return port; }
    public boolean online() { return enabled && !dirty && isStructureFormed() && port != null && port.getMainNode().isActive(); }
    public int accelerators() { return accelerators; }
    public boolean enabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; markDirty(); hardwareChanged(); }
    public List<EcoPart> parts(EcoPart.Role role) { return getAbilities(role.ability); }
    public List<EcoCpu> cpus() { return cpuSnapshot; }
    public int cpuOrdinal(EcoCpu cpu) { return cpuSnapshot.indexOf(cpu); }
    public List<IAssemblerPattern> patterns() { return patterns; }
    public boolean hasPattern(IPatternDetails details) { return patternIndex.containsKey(details.getDefinition()); }
    public void hardwareChanged() { dirty = true; }

    @Override protected StructureDefinition<?> createStructureDefinition() {
        Kind type = kind;
        if (type == null) type = metaTileEntityId.getPath().contains("molecular") ? Kind.ASSEMBLER
                : metaTileEntityId.getPath().contains("storage") ? Kind.STORAGE : Kind.COMPUTER;
        Kind selected = type;
        return StructureDefinition.getOrBuild("applygray:eco_" + type.id, () -> buildStructure(selected));
    }
    private static StructureDefinition<?> buildStructure(Kind type) {
        var casing = MetaBlocks.COMPUTER_CASING.getState(BlockComputerCasing.CasingType.COMPUTER_CASING);
        var vent = MetaBlocks.COMPUTER_CASING.getState(BlockComputerCasing.CasingType.COMPUTER_HEAT_VENT);
        var builder = type == Kind.ASSEMBLER ? DeclarativePatternBuilder.start()
                : DeclarativePatternBuilder.start(RelativeDirection.FRONT,
                        RelativeDirection.UP, RelativeDirection.RIGHT);
        if (type == Kind.ASSEMBLER) {
            builder.piece("front").aisle(EcoLayouts.HALL_FRONT).end()
                    .repeatablePiece("workshop", 1, EcoTiers.MAX_SEGMENTS)
                    .aisle(EcoLayouts.HALL_WORKER)
                    .aisle(EcoLayouts.HALL_BUS)
                    .withAisleChannel(GTStructureChannels.STRUCTURE_LENGTH.getName()).end()
                    .piece("rear").aisle(EcoLayouts.HALL_SERVICE)
                    .aisle(EcoLayouts.HALL_REAR).end()
                    .block('C', casing).block('H', vent)
                    .block('G', MetaBlocks.TRANSPARENT_CASING.getState(BlockGlassCasing.CasingType.FUSION_GLASS))
                    .where('F', frames(Materials.StainlessSteel)).where(' ', air())
                    .where('W', abilities(EcoPart.Role.WORKER.ability))
                    .where('B', abilities(EcoPart.Role.PATTERN_BUS.ability));
        } else {
            builder.piece("front").aisle(EcoLayouts.RACK_CAP).aisle(EcoLayouts.RACK_CONTROLLER).end();
            if (type == Kind.COMPUTER) {
                builder.repeatablePiece("rack", 1, EcoTiers.MAX_SEGMENTS).aisle(EcoLayouts.COMPUTER_SECTION)
                        .withAisleChannel(GTStructureChannels.STRUCTURE_LENGTH.getName()).end()
                        .where('T', abilities(EcoPart.Role.MEMORY.ability))
                        .where('P', abilities(EcoPart.Role.PARALLEL.ability))
                        .where('F', choice(abilities(EcoPart.Role.THREAD.ability), abilities(EcoPart.Role.HYPER_THREAD.ability)))
                        .block('U', MetaBlocks.COMPUTER_CASING.getState(BlockComputerCasing.CasingType.ADVANCED_COMPUTER_CASING));
            } else {
                builder.repeatablePiece("rack", 1, EcoTiers.MAX_SEGMENTS).aisle(EcoLayouts.STORAGE_SECTION)
                        .withAisleChannel(GTStructureChannels.STRUCTURE_LENGTH.getName()).end()
                        .where('T', abilities(EcoPart.Role.DRIVE.ability))
                        .where('J', choice(block(casing), abilities(EcoPart.Role.ENERGY.ability)));
            }
            builder.piece("rear").aisle(EcoLayouts.RACK_REAR).end().block('X', casing).block('H', vent);
        }
        return builder.self('S', EcoController.class).where('N', abilities(EcoNetWork.ability(type)))
                .buildStructureDefinition();
    }
    @Override protected void formStructure(FormedStructureView formed) {
        super.formStructure(formed);
        var ports = getAbilities(EcoNetWork.ability(kind));
        port = ports.isEmpty() ? null : ports.getFirst();
        // Publish only on the next formed tick: the structure engine finishes attaching parts after this hook.
        dirty = true;
    }
    @Override public void invalidateStructure() {
        EcoNetWork previous = port;
        for (EcoPart part : parts(EcoPart.Role.DRIVE)) part.persistCell();
        super.invalidateStructure();
        port = null;
        syncVisualActive();
        if (previous != null) previous.refreshServices();
        dirty = true;
    }
    @Override protected void updateFormedValid() {
        if (getWorld().isRemote) return;
        if (dirty) rebuildHardware();
        syncVisualActive();
        if (!online()) return;
        for (EcoCpu cpu : cpuSnapshot) cpu.reconcileReservation();
        if (cpuSnapshot.stream().anyMatch(cpu -> !cpu.installed && !cpu.isBusy())) dirty = true;
        if (kind == Kind.ASSEMBLER) for (EcoPart worker : parts(EcoPart.Role.WORKER)) worker.lane.tick(this);
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
    private void rebuildHardware() {
        dirty = false;
        if (kind == Kind.COMPUTER) {
            restoreCpus();
            long bytes = 0;
            for (EcoPart part : parts(EcoPart.Role.MEMORY)) {
                int tier = EcoItems.memoryTier(part.inventory.getStackInSlot(0));
                if (tier > 0) bytes = Math.addExact(bytes, EcoTiers.memory(tier));
            }
            memory.setCapacity(bytes);
            accelerators = parts(EcoPart.Role.PARALLEL).stream().mapToInt(p -> EcoTiers.accelerators(p.hardwareTier)).sum();
            cpus.values().forEach(cpu -> cpu.installed = false);
            for (EcoPart part : parts(EcoPart.Role.THREAD)) installCpus(part, false, EcoTiers.threads(part.hardwareTier, false));
            for (EcoPart part : parts(EcoPart.Role.HYPER_THREAD)) {
                installCpus(part, false, EcoTiers.normalThreadsOnHyperCore(part.hardwareTier));
                installCpus(part, true, EcoTiers.threads(part.hardwareTier, true));
            }
            // Retain retired CPU buffers until AE2 has returned their contents; never recycle active identities.
            for (EcoCpu cpu : cpus.values()) if (!cpu.installed && cpu.isBusy()) cpu.cancelJob();
            cpus.entrySet().removeIf(entry -> {
                EcoCpu cpu = entry.getValue();
                if (cpu.installed || cpu.isBusy()) return false;
                memory.release(cpu.id); cpu.destroy(); return true;
            });
            cpuSnapshot = List.copyOf(cpus.values());
        } else if (kind == Kind.ASSEMBLER) {
            patternIndex.clear();
            for (EcoPart bus : parts(EcoPart.Role.PATTERN_BUS)) {
                for (int slot = 0; slot < bus.inventory.getSlots(); slot++) {
                    ItemStack stack = bus.inventory.getStackInSlot(slot);
                    IPatternDetails details = PatternDetailsHelper.decodePattern(stack, getWorld());
                    if (details instanceof IAssemblerPattern pattern) patternIndex.putIfAbsent(details.getDefinition(), pattern);
                }
            }
            patterns = List.copyOf(patternIndex.values());
        }
        if (port != null) port.refreshServices();
        markDirty();
    }
    private void installCpus(EcoPart part, boolean hyper, int count) {
        for (int i = 0; i < count; i++) {
            String key = part.getPos().toLong() + "/" + (hyper ? "h" : "n") + i;
            EcoCpu cpu = cpus.computeIfAbsent(key, k -> new EcoCpu(this, UUID.randomUUID(), key, hyper));
            cpu.installed = true;
        }
    }
    public EcoPart freeLane(int multiplier) {
        var workers = parts(EcoPart.Role.WORKER);
        for (int i = 0; i < workers.size(); i++) {
            int index = (nextLane + i) % workers.size();
            EcoPart worker = workers.get(index);
            if (worker.lane.canAccept() && EcoConfig.batch(worker.hardwareTier) >= multiplier) {
                nextLane = (index + 1) % workers.size(); return worker;
            }
        }
        return null;
    }
    public List<StorageCell> cells() {
        var result = new ArrayList<StorageCell>();
        for (EcoPart drive : parts(EcoPart.Role.DRIVE)) { StorageCell cell = drive.cell(); if (cell != null) result.add(cell); }
        return result;
    }
    @Override public boolean onRightClick(EntityPlayer player, EnumHand hand, EnumFacing facing, CuboidRayTraceResult hit) {
        EcoGuiHandler.open(player, this); return true;
    }
    @Override public boolean hasMaintenanceMechanics() { return false; }
    @Override public boolean hasMufflerMechanics() { return false; }
    @Override public boolean isActive() {
        return getWorld() != null && getWorld().isRemote ? visualActive : online();
    }
    @Override public ICubeRenderer getBaseTexture(IMultiblockPart part) { return Textures.COMPUTER_CASING; }
    @Override protected ICubeRenderer getFrontOverlay() {
        return kind == Kind.ASSEMBLER ? Textures.LARGE_CIRCUIT_ASSEMBLER_OVERLAY
                : kind == Kind.STORAGE ? Textures.DATA_BANK_OVERLAY : Textures.HPCA_OVERLAY;
    }
    @SideOnly(Side.CLIENT)
    @Override public void addInformation(ItemStack stack, World world,
            List<String> tooltip, boolean advanced) {
        super.addInformation(stack, world, tooltip, advanced);
        tooltip.add(I18n.format(kind == Kind.ASSEMBLER
                ? "applygray.eco.tooltip.chamber" : "applygray.eco.tooltip.rack"));
        tooltip.add(I18n.format("applygray.eco.tooltip.interface"));
        tooltip.add(I18n.format("applygray.eco.tooltip.persist"));
    }
    private void syncVisualActive() {
        boolean active = online();
        if (visualActive != active) {
            visualActive = active;
            writeCustomData(STATUS_DATA, buffer -> buffer.writeBoolean(active));
        }
    }
    @Override public void writeInitialSyncData(PacketBuffer buffer) {
        super.writeInitialSyncData(buffer); buffer.writeBoolean(visualActive);
    }
    @Override public void receiveInitialSyncData(PacketBuffer buffer) {
        super.receiveInitialSyncData(buffer); visualActive = buffer.readBoolean();
    }
    @Override public void receiveCustomData(int id, PacketBuffer buffer) {
        if (id == STATUS_DATA) { visualActive = buffer.readBoolean(); scheduleRenderUpdate(); }
        else super.receiveCustomData(id, buffer);
    }
    private void saveEco(NBTTagCompound tag) {
        tag.setUniqueId("EcoId", identity);
        tag.setBoolean("EcoEnabled", enabled);
        NBTTagList list = new NBTTagList();
        if (pendingCpus != null) list = pendingCpus.copy();
        else for (EcoCpu cpu : cpus.values()) list.appendTag(cpu.save());
        tag.setTag("EcoCpus", list);
    }
    private void loadEco(NBTTagCompound tag) {
        if (tag.hasUniqueId("EcoId")) identity = tag.getUniqueId("EcoId");
        enabled = !tag.hasKey("EcoEnabled") || tag.getBoolean("EcoEnabled");
        pendingCpus = tag.getTagList("EcoCpus", 10).copy();
        dirty = true;
    }
    @Override public NBTTagCompound writeToNBT(NBTTagCompound tag) { super.writeToNBT(tag); saveEco(tag); return tag; }
    @Override public void readFromNBT(NBTTagCompound tag) { super.readFromNBT(tag); loadEco(tag); }
    @Override public void writeItemStackData(NBTTagCompound tag) { super.writeItemStackData(tag); saveEco(tag); }
    @Override public void initFromItemStackData(NBTTagCompound tag) { super.initFromItemStackData(tag); loadEco(tag); }
}
