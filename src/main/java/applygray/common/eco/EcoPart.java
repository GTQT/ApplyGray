package applygray.common.eco;
import applygray.common.items.ApplyGrayMetaItems;

import codechicken.lib.render.CCRenderState;
import codechicken.lib.render.pipeline.IVertexOperation;
import codechicken.lib.vec.Matrix4;
import gregtech.client.renderer.ICubeRenderer;
import gregtech.client.renderer.texture.Textures;
import net.minecraft.client.resources.I18n;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import ae2.api.storage.StorageCells;
import ae2.api.storage.cells.StorageCell;
import ae2.api.storage.cells.StorageCellStatistics;
import ae2.api.storage.cells.CellState;
import ae2.api.storage.MEStorageChangeListener;
import ae2.api.config.Actionable;
import ae2.api.networking.security.IActionSource;
import ae2.api.stacks.AEKey;
import ae2.api.stacks.KeyCounter;
import applygray.common.eco.gui.EcoGuiHandler;
import codechicken.lib.raytracer.CuboidRayTraceResult;
import gregtech.api.metatileentity.MetaTileEntity;
import gregtech.api.metatileentity.interfaces.IGregTechTileEntity;
import gregtech.api.metatileentity.multiblock.AbilityInstances;
import gregtech.api.metatileentity.multiblock.IMultiblockAbilityPart;
import gregtech.api.metatileentity.multiblock.MultiblockAbility;
import gregtech.common.metatileentities.multi.multiblockpart.MetaTileEntityMultiblockPart;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.items.CapabilityItemHandler;
import net.minecraftforge.items.ItemStackHandler;
import java.util.List;

/** ECO hardware, using GT abilities and AE2's cell inventory rather than parallel API abstractions. */
public final class EcoPart extends MetaTileEntityMultiblockPart implements IMultiblockAbilityPart<EcoPart> {
    public enum Role {
        MEMORY(1), THREAD(0), HYPER_THREAD(0), PARALLEL(0), DRIVE(1), ENERGY(0), WORKER(0), PATTERN_BUS(63);
        final int slots;
        public final MultiblockAbility<EcoPart> ability;
        Role(int slots) {
            this.slots = slots;
            ability = new MultiblockAbility<>("eco_" + name().toLowerCase(java.util.Locale.ROOT), EcoPart.class);
        }
    }

    public final Role role;
    public final int hardwareTier;
    public final ItemStackHandler inventory;
    public final EcoAssemblerLane lane;
    private BoundCell cell;
    private double energy;

    public EcoPart(ResourceLocation id, Role role, int tier) {
        super(id, tier);
        this.role = role;
        this.hardwareTier = tier;
        this.lane = role == Role.WORKER ? new EcoAssemblerLane(this) : null;
        this.inventory = new ItemStackHandler(role.slots) {
            @Override public boolean isItemValid(int slot, ItemStack stack) {
                if (stack.isEmpty()) return false;
                return switch (role) {
                    case MEMORY -> {
                        int tier = ApplyGrayMetaItems.memoryTier(stack);
                        yield tier > 0 && tier <= hardwareTier;
                    }
                    case DRIVE -> stack.getItem() instanceof EcoCellItem item && item.ecoTier() <= hardwareTier;
                    case PATTERN_BUS -> EcoAssemblerLane.isAssemblerPattern(stack, getWorld());
                    default -> false;
                };
            }
            @Override public int getSlotLimit(int slot) { return 1; }
            @Override public ItemStack extractItem(int slot, int amount, boolean simulate) {
                if (!simulate && amount > 0 && !getStackInSlot(slot).isEmpty()) detachCell();
                return super.extractItem(slot, amount, simulate);
            }
            @Override public void setStackInSlot(int slot, ItemStack stack) {
                detachCell();
                super.setStackInSlot(slot, stack);
            }
            @Override protected void onContentsChanged(int slot) {
                cell = null;
                markDirty();
                if (getController() instanceof EcoController controller) controller.hardwareChanged();
            }
        };
    }

    @Override public MetaTileEntity createMetaTileEntity(IGregTechTileEntity holder) {
        return new EcoPart(metaTileEntityId, role, hardwareTier);
    }
    @Override public MultiblockAbility<EcoPart> getAbility() { return role.ability; }
    @Override public List<MultiblockAbility<?>> getAbilities() { return List.of(role.ability); }
    @Override public void registerAbilities(AbilityInstances instances) {
        if (instances.isKey(role.ability)) instances.add(this);
    }
    @Override public boolean onRightClick(EntityPlayer player, EnumHand hand, EnumFacing facing,
                                           CuboidRayTraceResult hit) {
        EcoGuiHandler.open(player, this);
        return true;
    }
    @Override public <T> T getCapability(Capability<T> capability, EnumFacing side) {
        if (capability == CapabilityItemHandler.ITEM_HANDLER_CAPABILITY && role.slots > 0)
            return CapabilityItemHandler.ITEM_HANDLER_CAPABILITY.cast(inventory);
        return super.getCapability(capability, side);
    }
    @Override public ICubeRenderer getBaseTexture() {
        return Textures.COMPUTER_CASING;
    }
    @Override public void renderMetaTileEntity(CCRenderState state,
            Matrix4 translation, IVertexOperation[] pipeline) {
        super.renderMetaTileEntity(state, translation, pipeline);
        var texture = switch (role) {
            case THREAD, WORKER -> Textures.HPCA_COMPUTATION_OVERLAY;
            case HYPER_THREAD -> Textures.HPCA_ADVANCED_COMPUTATION_OVERLAY;
            case PARALLEL -> Textures.HPCA_BRIDGE_OVERLAY;
            case ENERGY -> Textures.ENERGY_IN;
            default -> Textures.DATA_ACCESS_HATCH;
        };
        texture.renderSided(getFrontFacing(), state, translation, pipeline);
    }
    @SideOnly(Side.CLIENT)
    @Override public void addInformation(ItemStack stack, World world,
            List<String> tooltip, boolean advanced) {
        super.addInformation(stack, world, tooltip, advanced);
        if (role == Role.WORKER) tooltip.add(I18n.format(
                "applygray.eco.tooltip.worker_capacity", EcoConfig.batch(hardwareTier),
                EcoConfig.assemblerCycleTicks, EcoConfig.aePerCraft));
        if (role == Role.THREAD || role == Role.HYPER_THREAD) tooltip.add(I18n.format(
                "applygray.eco.tooltip.threads", role == Role.THREAD ? EcoTiers.threads(hardwareTier, false)
                        : EcoTiers.normalThreadsOnHyperCore(hardwareTier),
                role == Role.HYPER_THREAD ? EcoTiers.threads(hardwareTier, true) : 0));
        if (role == Role.PARALLEL) tooltip.add(I18n.format(
                "applygray.eco.tooltip.parallel", EcoTiers.accelerators(hardwareTier)));
        if (role == Role.ENERGY) tooltip.add(I18n.format(
                "applygray.eco.tooltip.energy", EcoTiers.energy(hardwareTier)));
        if (role == Role.MEMORY || role == Role.DRIVE) tooltip.add(I18n.format(
                "applygray.eco.tooltip.drive", hardwareTier));
        if (role == Role.PATTERN_BUS) tooltip.add(I18n.format(
                "applygray.eco.tooltip.pattern_bus", EcoTiers.PATTERN_SLOTS));
        tooltip.add(I18n.format("applygray.eco.tooltip.persist"));
    }
    public StorageCell cell() {
        if (role != Role.DRIVE) return null;
        if (cell == null && !inventory.getStackInSlot(0).isEmpty()) {
            StorageCell nativeCell = StorageCells.getCellInventory(inventory.getStackInSlot(0), this::markDirty);
            if (nativeCell != null) cell = new BoundCell(nativeCell);
        }
        return cell;
    }
    public void persistCell() { if (cell != null) cell.persist(); }
    @Override public void onUnload() {
        detachCell();
        if (getWorld() != null && !getWorld().isRemote && getController() instanceof EcoController controller)
            controller.invalidateStructure();
        super.onUnload();
    }
    @Override public void onRemoval() { detachCell(); super.onRemoval(); }
    private void detachCell() {
        if (cell != null) { cell.persist(); cell.detach(); cell = null; }
    }
    public double storedEnergy() { return energy; }
    public double transferEnergy(double amount, boolean inject, boolean simulate) {
        if (role != Role.ENERGY || !Double.isFinite(amount) || amount <= 0) return 0;
        double moved = Math.min(amount, inject ? EcoTiers.energy(hardwareTier) - energy : energy);
        if (!simulate && moved > 0) {
            energy += inject ? moved : -moved;
            markDirty();
        }
        return moved;
    }
    private void writeHardware(NBTTagCompound tag) {
        persistCell();
        tag.setTag("EcoInventory", inventory.serializeNBT());
        tag.setDouble("EcoEnergy", energy);
        if (lane != null) tag.setTag("EcoLane", lane.save());
    }
    private void readHardware(NBTTagCompound tag) {
        detachCell();
        if (tag.hasKey("EcoInventory", 10)) inventory.deserializeNBT(tag.getCompoundTag("EcoInventory"));
        energy = Math.clamp(tag.getDouble("EcoEnergy"), 0, EcoTiers.energy(hardwareTier));
        if (!Double.isFinite(energy)) energy = 0;
        if (lane != null) lane.load(tag.getCompoundTag("EcoLane"));
    }
    @Override public NBTTagCompound writeToNBT(NBTTagCompound tag) {
        super.writeToNBT(tag); writeHardware(tag); return tag;
    }
    @Override public void readFromNBT(NBTTagCompound tag) { super.readFromNBT(tag); readHardware(tag); }
    @Override public void writeItemStackData(NBTTagCompound tag) { super.writeItemStackData(tag); writeHardware(tag); }
    @Override public void initFromItemStackData(NBTTagCompound tag) { super.initFromItemStackData(tag); readHardware(tag); }

    /** Close stale ME handles before the physical cell stack is copied out of the drive. */
    static final class BoundCell implements StorageCell, StorageCellStatistics {
        private final StorageCell delegate;
        private boolean attached = true;
        BoundCell(StorageCell delegate) { this.delegate = delegate; }
        void detach() { attached = false; }
        @Override public long insert(AEKey key, long amount, Actionable mode, IActionSource source) {
            return attached ? delegate.insert(key, amount, mode, source) : 0;
        }
        @Override public long extract(AEKey key, long amount, Actionable mode, IActionSource source) {
            return attached ? delegate.extract(key, amount, mode, source) : 0;
        }
        @Override public void getAvailableStacks(KeyCounter out) { if (attached) delegate.getAvailableStacks(out); }
        @Override public net.minecraft.util.text.ITextComponent getDescription() { return delegate.getDescription(); }
        @Override public CellState getStatus() { return attached ? delegate.getStatus() : CellState.EMPTY; }
        @Override public double getIdleDrain() { return attached ? delegate.getIdleDrain() : 0; }
        @Override public boolean canFitInsideCell() { return delegate.canFitInsideCell(); }
        @Override public void persist() { delegate.persist(); }
        @Override public void addListener(MEStorageChangeListener listener, Object token) { delegate.addListener(listener, token); }
        @Override public void removeListener(MEStorageChangeListener listener) { delegate.removeListener(listener); }
        @Override public boolean isPreferredStorageFor(AEKey key, IActionSource source) {
            return attached && delegate.isPreferredStorageFor(key, source);
        }
        @Override public boolean isStickyStorageFor(AEKey key, IActionSource source) {
            return attached && delegate.isStickyStorageFor(key, source);
        }
        @Override public long getUsedBytes() { return attached && delegate instanceof StorageCellStatistics s ? s.getUsedBytes() : 0; }
        @Override public long getTotalBytes() { return attached && delegate instanceof StorageCellStatistics s ? s.getTotalBytes() : 0; }
        @Override public long getStoredTypes() { return attached && delegate instanceof StorageCellStatistics s ? s.getStoredTypes() : 0; }
        @Override public long getTotalTypes() { return attached && delegate instanceof StorageCellStatistics s ? s.getTotalTypes() : 0; }
        @Override public int getBytesPerType() { return delegate instanceof StorageCellStatistics s ? s.getBytesPerType() : 0; }
    }
}
