package applygray.common.metatileentities.multi.multiblockpart.eco;

import gregtech.api.metatileentity.MetaTileEntity;
import gregtech.api.metatileentity.interfaces.IGregTechTileEntity;
import gregtech.client.renderer.ICubeRenderer;
import gregtech.client.renderer.texture.Textures;

import applygray.common.items.eco.EcoCellItem;

import ae2.api.config.Actionable;
import ae2.api.networking.security.IActionSource;
import ae2.api.stacks.AEKey;
import ae2.api.stacks.KeyCounter;
import ae2.api.storage.MEStorageChangeListener;
import ae2.api.storage.StorageCells;
import ae2.api.storage.cells.CellState;
import ae2.api.storage.cells.StorageCell;
import ae2.api.storage.cells.StorageCellStatistics;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.ITextComponent;

/** 存储矩阵驱动仓：插 ECO 存储元件，并把元件作为 ME 存储挂载。 */
public class MetaTileEntityEcoDrivePart extends MetaTileEntityEcoPart {

    private BoundCell cell;

    public MetaTileEntityEcoDrivePart(ResourceLocation metaTileEntityId, int tier) {
        super(metaTileEntityId, EcoPartRole.DRIVE, tier);
    }

    @Override
    public MetaTileEntity createMetaTileEntity(IGregTechTileEntity holder) {
        return new MetaTileEntityEcoDrivePart(metaTileEntityId, hardwareTier);
    }

    @Override
    protected boolean isItemValid(int slot, ItemStack stack) {
        return stack.getItem() instanceof EcoCellItem item && item.ecoTier() <= hardwareTier;
    }

    @Override
    protected ICubeRenderer partOverlay() {
        return Textures.DATA_ACCESS_HATCH;
    }

    @Override
    protected void addTooltipLines(java.util.List<String> tooltip) {
        tooltip.add(applygray.common.metatileentities.multi.electric.eco.EcoTooltips.line(
                "applygray.eco.drive.tooltip.0"));
        tooltip.add(applygray.common.metatileentities.multi.electric.eco.EcoTooltips.line(
                "applygray.eco.drive.tooltip.1"));
    }

    @Override
    protected void onInventoryChanged() {
        cell = null;
        super.onInventoryChanged();
    }

    @Override
    protected void onExtract() {
        detachCell();
    }

    @Override
    protected void onRemoved() {
        detachCell();
    }

    @Override
    public void onUnload() {
        detachCell();
        super.onUnload();
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound tag) {
        persistCell();
        return super.writeToNBT(tag);
    }

    @Override
    public void readFromNBT(NBTTagCompound tag) {
        detachCell();
        super.readFromNBT(tag);
    }

    @Override
    public void writeItemStackData(NBTTagCompound tag) {
        persistCell();
        super.writeItemStackData(tag);
    }

    @Override
    public void initFromItemStackData(NBTTagCompound tag) {
        detachCell();
        super.initFromItemStackData(tag);
    }

    public StorageCell cell() {
        if (cell == null && !inventory().getStackInSlot(0).isEmpty()) {
            StorageCell nativeCell = StorageCells.getCellInventory(inventory().getStackInSlot(0), this::markDirty);
            if (nativeCell != null) cell = new BoundCell(nativeCell);
        }
        return cell;
    }

    public void persistCell() {
        if (cell != null) cell.persist();
    }

    private void detachCell() {
        if (cell != null) {
            cell.persist();
            cell.detach();
            cell = null;
        }
    }

    /** 元件被拔出/替换前先断开 ME 句柄，避免旧库存继续被网络写入。 */
    static final class BoundCell implements StorageCell, StorageCellStatistics {

        private final StorageCell delegate;
        private boolean attached = true;

        BoundCell(StorageCell delegate) {
            this.delegate = delegate;
        }

        void detach() {
            attached = false;
        }

        @Override
        public long insert(AEKey key, long amount, Actionable mode, IActionSource source) {
            return attached ? delegate.insert(key, amount, mode, source) : 0;
        }

        @Override
        public long extract(AEKey key, long amount, Actionable mode, IActionSource source) {
            return attached ? delegate.extract(key, amount, mode, source) : 0;
        }

        @Override
        public void getAvailableStacks(KeyCounter out) {
            if (attached) delegate.getAvailableStacks(out);
        }

        @Override
        public ITextComponent getDescription() {
            return delegate.getDescription();
        }

        @Override
        public CellState getStatus() {
            return attached ? delegate.getStatus() : CellState.EMPTY;
        }

        @Override
        public double getIdleDrain() {
            return attached ? delegate.getIdleDrain() : 0;
        }

        @Override
        public boolean canFitInsideCell() {
            return delegate.canFitInsideCell();
        }

        @Override
        public void persist() {
            delegate.persist();
        }

        @Override
        public void addListener(MEStorageChangeListener listener, Object token) {
            delegate.addListener(listener, token);
        }

        @Override
        public void removeListener(MEStorageChangeListener listener) {
            delegate.removeListener(listener);
        }

        @Override
        public boolean isPreferredStorageFor(AEKey key, IActionSource source) {
            return attached && delegate.isPreferredStorageFor(key, source);
        }

        @Override
        public boolean isStickyStorageFor(AEKey key, IActionSource source) {
            return attached && delegate.isStickyStorageFor(key, source);
        }

        @Override
        public long getUsedBytes() {
            return attached && delegate instanceof StorageCellStatistics stats ? stats.getUsedBytes() : 0;
        }

        @Override
        public long getTotalBytes() {
            return attached && delegate instanceof StorageCellStatistics stats ? stats.getTotalBytes() : 0;
        }

        @Override
        public long getStoredTypes() {
            return attached && delegate instanceof StorageCellStatistics stats ? stats.getStoredTypes() : 0;
        }

        @Override
        public long getTotalTypes() {
            return attached && delegate instanceof StorageCellStatistics stats ? stats.getTotalTypes() : 0;
        }

        @Override
        public int getBytesPerType() {
            return delegate instanceof StorageCellStatistics stats ? stats.getBytesPerType() : 0;
        }
    }
}
