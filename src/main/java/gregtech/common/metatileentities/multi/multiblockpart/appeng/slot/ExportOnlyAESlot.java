package gregtech.common.metatileentities.multi.multiblockpart.appeng.slot;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.common.util.INBTSerializable;

import ae2.api.stacks.GenericStack;
import org.jetbrains.annotations.Nullable;

public abstract class ExportOnlyAESlot implements IConfigurableSlot, INBTSerializable<NBTTagCompound> {

    protected final static String CONFIG_TAG = "config";
    protected final static String STOCK_TAG = "stock";
    @Nullable
    protected GenericStack config;
    @Nullable
    protected GenericStack stock;

    public ExportOnlyAESlot(@Nullable GenericStack config, @Nullable GenericStack stock) {
        this.config = config;
        this.stock = stock;
    }

    public ExportOnlyAESlot() {
        this(null, null);
    }

    @Nullable
    public GenericStack requestStack() {
        if (this.stock != null && this.stock.amount() <= 0) {
            this.stock = null;
        }
        if (this.config == null || (this.stock != null && !this.config.what().matches(this.stock))) {
            return null;
        }
        if (this.stock == null) {
            return copy(this.config);
        }
        if (this.stock.amount() < this.config.amount()) {
            return copy(this.config, this.config.amount() - this.stock.amount());
        }
        return null;
    }

    @Nullable
    public GenericStack exceedStack() {
        if (this.stock != null && this.stock.amount() <= 0) {
            this.stock = null;
        }

        if (this.config == null && this.stock != null) {
            return copy(this.stock);
        }

        if (this.config != null && this.stock != null) {
            if (this.config.what().matches(this.stock) && this.config.amount() < this.stock.amount()) {
                return copy(this.stock, this.stock.amount() - this.config.amount());
            }
            if (!this.config.what().matches(this.stock)) {
                return copy(this.stock);
            }
        }

        return null;
    }

    public abstract void addStack(GenericStack stack);

    public abstract void setStack(@Nullable GenericStack stack);

    public abstract void decrementStock(long amount);

    @Override
    public NBTTagCompound serializeNBT() {
        NBTTagCompound tag = new NBTTagCompound();
        if (this.config != null) {
            tag.setTag(CONFIG_TAG, GenericStack.writeTag(this.config));
        }
        if (this.stock != null) {
            tag.setTag(STOCK_TAG, GenericStack.writeTag(this.stock));
        }
        return tag;
    }

    @Override
    public @Nullable GenericStack getConfig() {
        return this.config;
    }

    @Override
    public @Nullable GenericStack getStock() {
        return this.stock;
    }

    @Override
    public void setConfig(@Nullable GenericStack val) {
        this.config = val;
    }

    @Override
    public void setStock(@Nullable GenericStack val) {
        this.stock = val;
    }

    protected static GenericStack copy(GenericStack stack) {
        return new GenericStack(stack.what(), stack.amount());
    }

    protected static GenericStack copy(GenericStack stack, long amount) {
        return new GenericStack(stack.what(), amount);
    }
}
