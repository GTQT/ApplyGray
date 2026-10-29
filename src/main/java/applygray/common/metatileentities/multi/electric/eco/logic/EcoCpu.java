package applygray.common.metatileentities.multi.electric.eco.logic;

import ae2.api.networking.IGrid;
import ae2.api.networking.IGridNode;
import ae2.api.networking.crafting.CraftingCpuGroup;
import ae2.api.networking.crafting.CraftingJobOptions;
import ae2.api.networking.crafting.ICraftingPlan;
import ae2.api.networking.crafting.ICraftingRequester;
import ae2.api.networking.crafting.ICraftingSubmitResult;
import ae2.api.networking.security.IActionSource;
import ae2.crafting.execution.CraftingSubmitResult;
import ae2.me.cluster.implementations.CraftingCPUCluster;
import applygray.common.metatileentities.multi.electric.eco.MetaTileEntityEcoComputer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.World;
import java.util.UUID;

/** Native AE2 CPU with a shared storage budget and a real GT holder as its action source. */
public final class EcoCpu extends CraftingCPUCluster {
    public final MetaTileEntityEcoComputer owner;
    public final UUID id;
    public final String slot;
    public final boolean hyper;
    public boolean installed;
    private long admittingBytes = -1;
    public UUID identity() { return id; }

    public EcoCpu(MetaTileEntityEcoComputer owner, UUID id, String slot, boolean hyper) {
        super(owner.getPos(), owner.getPos());
        this.owner = owner;
        this.id = id;
        this.slot = slot;
        this.hyper = hyper;
    }

    @Override public IGridNode getNode() { return owner.port() == null ? null : owner.port().getMainNode().getNode(); }
    @Override public IGrid getGrid() { IGridNode node = getNode(); return node == null ? null : node.grid(); }
    @Override public World getLevel() { return owner.getWorld(); }
    @Override public net.minecraft.util.math.BlockPos getCorePos() { return owner.getPos(); }
    @Override public IActionSource getSrc() { return owner.port() == null ? IActionSource.empty() : owner.port().source(); }
    @Override public void markDirty() { owner.markDirty(); }
    @Override public boolean isActive() { return owner.online() && (installed || isBusy()); }
    @Override public int getCoProcessors() { return owner.accelerators(); }
    @Override public ITextComponent getName() {
        return new TextComponentString("ECO CPU " + (owner.cpuOrdinal(this) + 1) + (hyper ? " [HT]" : ""));
    }
    @Override public CraftingCpuGroup getCpuListGroup() {
        return new CraftingCpuGroup(new ResourceLocation("applygray", "eco/" + owner.identity()), owner.cpuOrdinal(this));
    }
    @Override public long getAvailableStorage() {
        if (admittingBytes >= 0) return admittingBytes;
        if (isBusy()) return owner.memory().reserved(id);
        if (!installed) return 0;
        long available = owner.memory().available();
        return hyper ? (available / 11) * 10 + (available % 11) * 10 / 11 : available;
    }
    @Override public boolean isBusy() {
        return craftingLogic.hasJob() || !craftingLogic.getInventory().list.isEmpty();
    }
    /** Job merging would need to reserve its incremental bytes; expose independent native jobs instead. */
    @Override public boolean canMergeJob(ICraftingPlan plan) { return false; }
    @Override public ICraftingSubmitResult mergeJob(IGrid grid, ICraftingPlan plan, IActionSource source) {
        return CraftingSubmitResult.CPU_BUSY;
    }
    @Override public ICraftingSubmitResult mergeJob(IGrid grid, ICraftingPlan plan, IActionSource source, int priority) {
        return CraftingSubmitResult.CPU_BUSY;
    }
    @Override public ICraftingSubmitResult submitJob(IGrid grid, ICraftingPlan plan, IActionSource source,
                                                    ICraftingRequester requester) {
        return submitJob(grid, plan, source, requester, CraftingJobOptions.DEFAULT);
    }
    @Override public ICraftingSubmitResult submitJob(IGrid grid, ICraftingPlan plan, IActionSource source,
                                                    ICraftingRequester requester, CraftingJobOptions options) {
        if (!isActive() || !installed) return CraftingSubmitResult.CPU_OFFLINE;
        if (isBusy()) return CraftingSubmitResult.CPU_BUSY;
        if (!owner.memory().reserve(id, plan.bytes(), hyper)) return CraftingSubmitResult.CPU_TOO_SMALL;
        boolean accepted = false;
        admittingBytes = plan.bytes();
        try {
            ICraftingSubmitResult result = super.submitJob(grid, plan, source, requester, options);
            accepted = result.successful();
            if (accepted) owner.markDirty();
            return result;
        } finally {
            admittingBytes = -1;
            if (!accepted) owner.memory().release(id);
        }
    }
    public void reconcileReservation() {
        // AE2 retains cancelled job inputs in its native inventory until they can be returned to the network.
        if (!craftingLogic.hasJob()) {
            craftingLogic.storeItems();
            owner.memory().release(id);
        }
    }
    public NBTTagCompound save() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setUniqueId("id", id);
        tag.setString("slot", slot);
        tag.setBoolean("hyper", hyper);
        tag.setLong("reserved", owner.memory().reserved(id));
        NBTTagCompound nativeData = new NBTTagCompound();
        super.writeToNBT(nativeData);
        tag.setTag("native", nativeData);
        return tag;
    }
}
