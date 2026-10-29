package applygray.common.metatileentities.multi.multiblockpart.eco;

import gregtech.api.metatileentity.multiblock.AbilityInstances;
import gregtech.api.metatileentity.multiblock.IMultiblockAbilityPart;
import gregtech.api.metatileentity.multiblock.MultiblockAbility;
import gregtech.client.renderer.ICubeRenderer;
import gregtech.client.renderer.texture.Textures;
import gregtech.common.metatileentities.multi.multiblockpart.MetaTileEntityMultiblockPart;

import applygray.common.metatileentities.multi.electric.eco.MetaTileEntityEcoController;
import applygray.common.metatileentities.multi.electric.eco.gui.EcoGuiHandler;

import net.minecraft.client.resources.I18n;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.World;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import net.minecraftforge.items.CapabilityItemHandler;
import net.minecraftforge.items.ItemStackHandler;

import codechicken.lib.raytracer.CuboidRayTraceResult;
import codechicken.lib.render.CCRenderState;
import codechicken.lib.render.pipeline.IVertexOperation;
import codechicken.lib.vec.Matrix4;
import org.jspecify.annotations.NonNull;

import java.util.List;

/**
 * ECO 仓室公共部分：GT 多方块仓室注册、单格库存、右键 UI、NBT 与贴图。
 *
 * <p>每个 role 一个具体类，和 GT 的 {@code MetaTileEntityHPCAComponent} 体系同构；
 * 基类只处理所有仓室都一样的部分。</p>
 */
public abstract class MetaTileEntityEcoPart extends MetaTileEntityMultiblockPart implements
        IMultiblockAbilityPart<MetaTileEntityEcoPart> {

    public final EcoPartRole role;
    public final int hardwareTier;
    private final ItemStackHandler inventory;

    protected MetaTileEntityEcoPart(ResourceLocation metaTileEntityId, EcoPartRole role, int tier) {
        super(metaTileEntityId, tier);
        this.role = role;
        this.hardwareTier = tier;
        this.inventory = new ItemStackHandler(role.slots) {
            @Override
            public boolean isItemValid(int slot, @NonNull ItemStack stack) {
                return MetaTileEntityEcoPart.this.isItemValid(slot, stack);
            }

            @Override
            public int getSlotLimit(int slot) {
                return 1;
            }

            @Override
            public @NonNull ItemStack extractItem(int slot, int amount, boolean simulate) {
                if (!simulate && amount > 0 && !getStackInSlot(slot).isEmpty()) {
                    MetaTileEntityEcoPart.this.onExtract();
                }
                return super.extractItem(slot, amount, simulate);
            }

            @Override
            public void setStackInSlot(int slot, ItemStack stack) {
                MetaTileEntityEcoPart.this.onExtract();
                super.setStackInSlot(slot, stack);
            }

            @Override
            protected void onContentsChanged(int slot) {
                MetaTileEntityEcoPart.this.onInventoryChanged();
            }
        };
    }

    public final ItemStackHandler inventory() {
        return inventory;
    }

    public final MetaTileEntityEcoController controller() {
        return getController() instanceof MetaTileEntityEcoController controller ? controller : null;
    }

    @Override
    public MultiblockAbility<MetaTileEntityEcoPart> getAbility() {
        return EcoAbilities.byRole(role);
    }

    @Override
    public @NonNull List<MultiblockAbility<?>> getAbilities() {
        return List.of(getAbility());
    }

    @Override
    public void registerAbilities(AbilityInstances instances) {
        if (instances.isKey(getAbility())) instances.add(this);
    }

    @Override
    public boolean onRightClick(EntityPlayer player, EnumHand hand, EnumFacing facing, CuboidRayTraceResult hit) {
        EcoGuiHandler.open(player, this);
        return true;
    }

    @Override
    public ICubeRenderer getBaseTexture() {
        if (getController() == null) return Textures.COMPUTER_CASING;
        return super.getBaseTexture();
    }

    @Override
    public void renderMetaTileEntity(CCRenderState renderState, Matrix4 translation, IVertexOperation[] pipeline) {
        super.renderMetaTileEntity(renderState, translation, pipeline);
        if (shouldRenderOverlay()) {
            partOverlay().renderSided(getFrontFacing(), renderState, translation, pipeline);
        }
    }

    @SideOnly(Side.CLIENT)
    @Override
    public void addInformation(ItemStack stack, World world, @NonNull List<String> tooltip, boolean advanced) {
        super.addInformation(stack, world, tooltip, advanced);
        addTooltipLines(tooltip);
    }

    /** 子类按 NeoECO / PrismPlan 的原文追加说明。 */
    @SideOnly(Side.CLIENT)
    protected void addTooltipLines(List<String> tooltip) {}

    @Override
    public <T> T getCapability(Capability<T> capability, EnumFacing side) {
        if (capability == CapabilityItemHandler.ITEM_HANDLER_CAPABILITY && role.slots > 0) {
            return CapabilityItemHandler.ITEM_HANDLER_CAPABILITY.cast(inventory);
        }
        return super.getCapability(capability, side);
    }

    @Override
    public void onUnload() {
        if (getWorld() != null && !getWorld().isRemote && controller() != null) controller().invalidateStructure();
        super.onUnload();
    }

    @Override
    public void onRemoval() {
        onRemoved();
        super.onRemoval();
    }

    /** 库存是否接受该物品；默认不接收。 */
    protected boolean isItemValid(int slot, ItemStack stack) {
        return false;
    }

    /** 库存内容变化后的通用刷新；子类可覆盖以清掉缓存。 */
    protected void onInventoryChanged() {
        markDirty();
        if (controller() != null) controller().hardwareChanged();
    }

    /** 物品被取出/替换前的清理；驱动仓用它关闭 ME 句柄。 */
    protected void onExtract() {}

    protected void onRemoved() {}

    protected abstract ICubeRenderer partOverlay();

    protected void writeExtra(NBTTagCompound tag) {}

    protected void readExtra(NBTTagCompound tag) {}

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound tag) {
        super.writeToNBT(tag);
        tag.setTag("EcoInventory", inventory.serializeNBT());
        writeExtra(tag);
        return tag;
    }

    @Override
    public void readFromNBT(NBTTagCompound tag) {
        super.readFromNBT(tag);
        if (tag.hasKey("EcoInventory", 10)) inventory.deserializeNBT(tag.getCompoundTag("EcoInventory"));
        readExtra(tag);
    }

    @Override
    public void writeItemStackData(NBTTagCompound tag) {
        super.writeItemStackData(tag);
        tag.setTag("EcoInventory", inventory.serializeNBT());
        writeExtra(tag);
    }

    @Override
    public void initFromItemStackData(NBTTagCompound tag) {
        super.initFromItemStackData(tag);
        if (tag.hasKey("EcoInventory", 10)) inventory.deserializeNBT(tag.getCompoundTag("EcoInventory"));
        readExtra(tag);
    }
}
