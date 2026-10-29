package applygray.common.metatileentities.multi.electric.eco;

import codechicken.lib.render.CCRenderState;
import codechicken.lib.render.pipeline.IVertexOperation;
import codechicken.lib.vec.Matrix4;

import gregtech.api.metatileentity.multiblock.IMultiblockPart;
import gregtech.api.metatileentity.multiblock.MultiblockWithDisplayBase;
import gregtech.api.pattern.FormedStructureView;
import gregtech.client.renderer.ICubeRenderer;
import gregtech.client.renderer.texture.Textures;

import applygray.common.metatileentities.multi.electric.eco.gui.EcoGuiHandler;
import applygray.common.metatileentities.multi.multiblockpart.eco.EcoAbilities;
import applygray.common.metatileentities.multi.multiblockpart.eco.EcoPartRole;
import applygray.common.metatileentities.multi.multiblockpart.eco.MetaTileEntityEcoNetworkPart;
import applygray.common.metatileentities.multi.multiblockpart.eco.MetaTileEntityEcoPart;

import gregtech.common.blocks.BlockComputerCasing;
import gregtech.common.blocks.MetaBlocks;

import net.minecraft.block.state.IBlockState;
import net.minecraft.client.resources.I18n;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import codechicken.lib.raytracer.CuboidRayTraceResult;
import org.jspecify.annotations.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * ECO 控制器公共部分：结构、ME 接口、暂停状态、持久化、UI 入口与视觉状态同步。
 *
 * <p>四个组分各自是一个 GT 多方块类；这里只放共同生命周期，具体硬件统计与工作逻辑由子类实现。</p>
 */
public abstract class MetaTileEntityEcoController extends MultiblockWithDisplayBase {

    private static final int STATUS_DATA = 0x45434F;

    private final EcoControllerType type;
    private MetaTileEntityEcoNetworkPart port;
    private UUID identity = UUID.randomUUID();
    private boolean dirty = true;
    private boolean enabled = true;
    private boolean visualActive;

    protected MetaTileEntityEcoController(ResourceLocation metaTileEntityId, EcoControllerType type) {
        super(metaTileEntityId);
        this.type = type;
    }

    public final EcoControllerType type() {
        return type;
    }

    public final MetaTileEntityEcoNetworkPart port() {
        return port;
    }

    public final UUID identity() {
        return identity;
    }

    public final boolean enabled() {
        return enabled;
    }

    public final void setEnabled(boolean value) {
        enabled = value;
        markDirty();
        hardwareChanged();
    }

    public final void hardwareChanged() {
        dirty = true;
    }

    public final boolean online() {
        return enabled && !dirty && isStructureFormed() && port != null && port.getMainNode().isActive();
    }

    public final List<MetaTileEntityEcoPart> parts(EcoPartRole role) {
        return getAbilities(EcoAbilities.byRole(role));
    }

    public final <T extends MetaTileEntityEcoPart> List<T> parts(EcoPartRole role, Class<T> partType) {
        List<T> result = new ArrayList<>();
        for (MetaTileEntityEcoPart part : parts(role)) {
            if (partType.isInstance(part)) result.add(partType.cast(part));
        }
        return result;
    }

    @Override
    protected void formStructure(@NonNull FormedStructureView formed) {
        super.formStructure(formed);
        var ports = getAbilities(EcoAbilities.network(type));
        port = ports.isEmpty() ? null : ports.getFirst();
        // The structure engine attaches every part after this hook, so publish on the next formed tick.
        dirty = true;
    }

    @Override
    public void invalidateStructure() {
        onInvalidateStructure();
        MetaTileEntityEcoNetworkPart previous = port;
        super.invalidateStructure();
        port = null;
        syncVisualActive();
        if (previous != null) previous.refreshServices();
        dirty = true;
    }

    @Override
    public void onUnload() {
        if (getWorld() != null && !getWorld().isRemote) invalidateStructure();
        super.onUnload();
    }

    @Override
    protected void updateFormedValid() {
        if (getWorld().isRemote) return;
        if (dirty) rebuildHardware();
        syncVisualActive();
        if (online()) onOnlineTick();
    }

    private void rebuildHardware() {
        dirty = false;
        rebuildParts();
        if (port != null) port.refreshServices();
        markDirty();
    }

    /** 子类在这里重算仓室硬件；调用时结构已经完成成型。 */
    protected void rebuildParts() {}

    /** 在线时每 tick 的工作逻辑。 */
    protected void onOnlineTick() {}

    /** 拆解前的清理，例如驱动仓持久化元件。 */
    protected void onInvalidateStructure() {}

    protected void saveExtra(NBTTagCompound tag) {}

    protected void loadExtra(NBTTagCompound tag) {}

    @Override
    public boolean onRightClick(EntityPlayer player, EnumHand hand, EnumFacing facing, CuboidRayTraceResult hit) {
        EcoGuiHandler.open(player, this);
        return true;
    }

    @Override
    public boolean hasMaintenanceMechanics() {
        return false;
    }

    @Override
    public boolean hasMufflerMechanics() {
        return false;
    }

    @Override
    public boolean isActive() {
        return getWorld() != null && getWorld().isRemote ? visualActive : online();
    }

    @Override
    public ICubeRenderer getBaseTexture(IMultiblockPart part) {
        return Textures.COMPUTER_CASING;
    }

    /**
     * JEI / 结构预览的正面。CEU 的 DataBank、AssemblyLine、DistillationTower 都把它固定成 SOUTH，
     * 否则预览会按未放置机器的默认朝北渲染，和实际“控制器朝外”的成型方向差 180°。
     */
    @Override
    public @NonNull EnumFacing getPreviewFrontFacing() {
        return EnumFacing.SOUTH;
    }

    /**
     * 反射会获取这个方法来给机器外壳上CTM
     */
    public static IBlockState getCasingState() {
        return MetaBlocks.COMPUTER_CASING.getState(BlockComputerCasing.CasingType.COMPUTER_CASING);
    }

    protected @NonNull ICubeRenderer getFrontOverlay() {
        return Textures.HPCA_OVERLAY;
    }

    @SideOnly(Side.CLIENT)
    @Override
    public void renderMetaTileEntity(CCRenderState renderState, Matrix4 translation, IVertexOperation[] pipeline) {
        super.renderMetaTileEntity(renderState, translation, pipeline);
        getFrontOverlay().renderOrientedState(renderState, translation, pipeline, getFrontFacing(), true,
                isStructureFormed());
    }

    @SideOnly(Side.CLIENT)
    @Override
    public void addInformation(ItemStack stack, World world, List<String> tooltip, boolean advanced) {
        super.addInformation(stack, world, tooltip, advanced);
        addTooltipLines(tooltip);
    }

    /** 子类按 NeoECO / PrismPlan 的原文追加说明。 */
    @SideOnly(Side.CLIENT)
    protected void addTooltipLines(List<String> tooltip) {}

    private void syncVisualActive() {
        boolean active = online();
        if (visualActive != active) {
            visualActive = active;
            writeCustomData(STATUS_DATA, buffer -> buffer.writeBoolean(active));
        }
    }

    @Override
    public void writeInitialSyncData(PacketBuffer buffer) {
        super.writeInitialSyncData(buffer);
        buffer.writeBoolean(visualActive);
    }

    @Override
    public void receiveInitialSyncData(PacketBuffer buffer) {
        super.receiveInitialSyncData(buffer);
        visualActive = buffer.readBoolean();
    }

    @Override
    public void receiveCustomData(int id, PacketBuffer buffer) {
        if (id == STATUS_DATA) {
            visualActive = buffer.readBoolean();
            scheduleRenderUpdate();
        } else {
            super.receiveCustomData(id, buffer);
        }
    }

    private void saveEco(NBTTagCompound tag) {
        tag.setUniqueId("EcoId", identity);
        tag.setBoolean("EcoEnabled", enabled);
        saveExtra(tag);
    }

    private void loadEco(NBTTagCompound tag) {
        if (tag.hasUniqueId("EcoId")) identity = tag.getUniqueId("EcoId");
        enabled = !tag.hasKey("EcoEnabled") || tag.getBoolean("EcoEnabled");
        loadExtra(tag);
        dirty = true;
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound tag) {
        super.writeToNBT(tag);
        saveEco(tag);
        return tag;
    }

    @Override
    public void readFromNBT(NBTTagCompound tag) {
        super.readFromNBT(tag);
        loadEco(tag);
    }

    @Override
    public void writeItemStackData(NBTTagCompound tag) {
        super.writeItemStackData(tag);
        saveEco(tag);
    }

    @Override
    public void initFromItemStackData(NBTTagCompound tag) {
        super.initFromItemStackData(tag);
        loadEco(tag);
    }
}
