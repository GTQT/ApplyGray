package gregtech.common.metatileentities.multi.multiblockpart.appeng.slot;

import gregtech.api.capability.INotifiableHandler;
import gregtech.api.metatileentity.MetaTileEntity;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidTankInfo;
import net.minecraftforge.fluids.IFluidTank;
import net.minecraftforge.fluids.capability.FluidTankProperties;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fluids.capability.IFluidTankProperties;

import ae2.api.stacks.AEFluidKey;
import ae2.api.stacks.GenericStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

public class ExportOnlyAEFluidSlot extends ExportOnlyAESlot
        implements IFluidTank, INotifiableHandler, IFluidHandler {

    private final List<MetaTileEntity> notifiableEntities = new ArrayList<>();
    @Nullable
    private MetaTileEntity holder;

    public ExportOnlyAEFluidSlot(@Nullable MetaTileEntity holder, @Nullable GenericStack config,
                                 @Nullable GenericStack stock, @Nullable MetaTileEntity entityToNotify) {
        super(config, stock);
        this.holder = holder;
        if (entityToNotify != null) {
            this.notifiableEntities.add(entityToNotify);
        }
    }

    public ExportOnlyAEFluidSlot(MetaTileEntity holder, MetaTileEntity entityToNotify) {
        this(holder, null, null, entityToNotify);
    }

    public ExportOnlyAEFluidSlot() {
        this(null, null, null, null);
    }

    @Override
    public void decrementStock(long amount) {
        if (stock != null) {
            setStack(copy(stock, Math.max(0, stock.amount() - amount)));
        }
    }

    @Override
    public void addStack(GenericStack stack) {
        if (!(stack.what() instanceof AEFluidKey)) {
            return;
        }
        if (this.stock == null || !this.stock.what().equals(stack.what())) {
            this.stock = copy(stack);
        } else {
            this.stock = new GenericStack(this.stock.what(), this.stock.amount() + stack.amount());
        }
        trigger();
    }

    @Override
    public void setStack(@Nullable GenericStack stack) {
        if (stack != null && !(stack.what() instanceof AEFluidKey)) {
            return;
        }
        if (this.stock == null && stack == null) {
            return;
        }
        this.stock = stack == null || stack.amount() <= 0 ? null : copy(stack);
        trigger();
    }

    @Override
    public void setStock(@Nullable GenericStack stack) {
        setStack(stack);
    }

    @Override
    public void deserializeNBT(NBTTagCompound nbt) {
        this.config = readStack(nbt, CONFIG_TAG);
        this.stock = readStack(nbt, STOCK_TAG);
    }

    @Nullable
    private static GenericStack readStack(NBTTagCompound owner, String key) {
        return owner.hasKey(key) ? GenericStack.readTag(owner.getCompoundTag(key)) : null;
    }

    @Nullable
    @Override
    public FluidStack getFluid() {
        if (stock != null && stock.what() instanceof AEFluidKey fluidKey) {
            return fluidKey.toStack(saturatingInt(stock.amount()));
        }
        return null;
    }

    @Override
    public int getFluidAmount() {
        return stock == null ? 0 : saturatingInt(stock.amount());
    }

    @Override
    public int getCapacity() {
        return 0;
    }

    @Override
    public FluidTankInfo getInfo() {
        return new FluidTankInfo(this);
    }

    @Override
    public IFluidTankProperties[] getTankProperties() {
        return new IFluidTankProperties[] {new FluidTankProperties(getFluid(), 0)};
    }

    @Override
    public int fill(FluidStack resource, boolean doFill) {
        return 0;
    }

    @Nullable
    @Override
    public FluidStack drain(FluidStack resource, boolean doDrain) {
        FluidStack fluid = getFluid();
        return fluid != null && fluid.isFluidEqual(resource) ? drain(resource.amount, doDrain) : null;
    }

    @Nullable
    @Override
    public FluidStack drain(int maxDrain, boolean doDrain) {
        if (stock == null || !(stock.what() instanceof AEFluidKey fluidKey)) {
            return null;
        }
        int drained = (int) Math.min(stock.amount(), maxDrain);
        FluidStack result = fluidKey.toStack(drained);
        if (doDrain) {
            long remaining = stock.amount() - drained;
            stock = remaining == 0 ? null : copy(stock, remaining);
            trigger();
        }
        return result;
    }

    @Override
    public void addNotifiableMetaTileEntity(MetaTileEntity metaTileEntity) {
        notifiableEntities.add(metaTileEntity);
    }

    @Override
    public void removeNotifiableMetaTileEntity(MetaTileEntity metaTileEntity) {
        notifiableEntities.remove(metaTileEntity);
    }

    protected void trigger() {
        for (MetaTileEntity metaTileEntity : notifiableEntities) {
            if (metaTileEntity != null && metaTileEntity.isValid()) {
                addToNotifiedList(metaTileEntity, this, false);
            }
        }
        if (holder != null) {
            holder.markDirty();
        }
    }

    @Override
    public @NotNull ExportOnlyAEFluidSlot copy() {
        return new ExportOnlyAEFluidSlot(holder,
                config == null ? null : copy(config), stock == null ? null : copy(stock), null);
    }

    protected MetaTileEntity getHolder() {
        return holder;
    }

    private static int saturatingInt(long amount) {
        return amount > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) Math.max(0, amount);
    }
}
