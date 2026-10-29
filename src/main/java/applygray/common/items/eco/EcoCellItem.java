package applygray.common.items.eco;

import net.minecraft.client.util.ITooltipFlag;
import ae2.api.config.FuzzyMode;
import ae2.api.stacks.AEKeyType;
import ae2.api.storage.cells.IBasicCellItem;
import ae2.api.upgrades.IUpgradeInventory;
import ae2.api.upgrades.UpgradeInventories;
import ae2.items.contents.CellConfig;
import ae2.util.ConfigInventory;
import gregtech.api.items.metaitem.MetaItem;
import gregtech.api.items.metaitem.StandardMetaItem;
import applygray.api.ApplyGrayAPI;
import applygray.common.metatileentities.multi.electric.eco.EcoTiers;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;
import java.util.List;
import java.util.Set;

/** GT MetaItem shell; AE2 owns cell accounting, filtering, storage and persistence. */
public final class EcoCellItem extends StandardMetaItem implements IBasicCellItem {
    private final int tier;
    private final boolean fluid;

    /** 子项句柄，供配方与 JEI 直接引用，避免运行时按名字反查。 */
    public MetaItem<?>.MetaValueItem stack;

    public EcoCellItem(int tier, boolean fluid) {
        this.tier = tier;
        this.fluid = fluid;
        // 注册名与模型路径、lang key 同源，避免同一个元件在多处各写一遍名字
        setRegistryName(ApplyGrayAPI.id("eco_" + (fluid ? "fluid" : "item") + "_cell_" + tier));
    }

    public int ecoTier() { return tier; }
    @Override public void registerSubItems() {
        stack = addItem(0, "eco." + (fluid ? "fluid" : "item") + "_cell." + tier).setMaxStackSize(1);
    }
    @Override public AEKeyType getKeyType() { return fluid ? AEKeyType.fluids() : AEKeyType.items(); }
    @Override public int getBytes(ItemStack stack) { return EcoTiers.storageBytes(tier); }
    @Override public int getBytesPerType(ItemStack stack) { return EcoTiers.bytesPerType(tier); }
    @Override public int getTotalTypes(ItemStack stack) { return fluid ? 25 : 315; }
    @Override public double getIdleDrain() { return EcoTiers.idleDrain(tier); }
    @Override public boolean isStorageCell(ItemStack stack) { return stack.getItemDamage() == 0; }
    @Override public IUpgradeInventory getUpgrades(ItemStack stack) { return UpgradeInventories.forItem(stack, 2); }
    @Override public ConfigInventory getConfigInventory(ItemStack stack) {
        return CellConfig.create(Set.of(getKeyType()), stack, getTotalTypes(stack));
    }
    @Override public FuzzyMode getFuzzyMode(ItemStack stack) {
        try { return FuzzyMode.valueOf(stack.hasTagCompound() ? stack.getTagCompound().getString("ecoFuzzy") : ""); }
        catch (IllegalArgumentException ignored) { return FuzzyMode.IGNORE_ALL; }
    }
    @Override public void setFuzzyMode(ItemStack stack, FuzzyMode mode) {
        if (!stack.hasTagCompound()) stack.setTagCompound(new NBTTagCompound());
        stack.getTagCompound().setString("ecoFuzzy", mode.name());
    }
    @net.minecraftforge.fml.relauncher.SideOnly(net.minecraftforge.fml.relauncher.Side.CLIENT)
    @Override public void addInformation(ItemStack stack, World world, List<String> lines,
                                         ITooltipFlag advanced) {
        super.addInformation(stack, world, lines, advanced);
        addCellInformationToTooltip(stack, lines);
    }
}
