package applygray.common.eco;

import ae2.api.crafting.IAssemblerPattern;
import codechicken.lib.render.CCRenderState;
import codechicken.lib.render.pipeline.IVertexOperation;
import codechicken.lib.vec.Matrix4;
import gregtech.client.renderer.ICubeRenderer;
import gregtech.client.renderer.texture.Textures;
import gregtech.common.ConfigHolder;
import net.minecraft.client.resources.I18n;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import ae2.api.config.AccessRestriction;
import ae2.api.config.Actionable;
import ae2.api.config.PowerMultiplier;
import ae2.api.crafting.IPatternDetails;
import ae2.api.networking.IGridNodeListener;
import ae2.api.networking.IManagedGridNode;
import ae2.api.networking.crafting.ICraftingCpuProvider;
import ae2.api.networking.crafting.ICraftingProvider;
import ae2.api.networking.energy.IAEPowerStorage;
import ae2.api.networking.events.GridCraftingCpuChange;
import ae2.api.networking.events.GridPowerStorageChanged;
import ae2.api.networking.security.IActionSource;
import ae2.api.stacks.KeyCounter;
import ae2.api.storage.IStorageMounts;
import ae2.api.storage.IStorageProvider;
import codechicken.lib.raytracer.CuboidRayTraceResult;
import applygray.common.eco.gui.EcoGuiHandler;
import gregtech.api.metatileentity.MetaTileEntity;
import gregtech.api.metatileentity.interfaces.IGregTechTileEntity;
import gregtech.api.metatileentity.multiblock.*;
import gregtech.common.metatileentities.multi.multiblockpart.appeng.MetaTileEntityAEHostablePart;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.ResourceLocation;
import java.util.*;

/** One channel-qualified node per ECO machine. Services expose snapshots; queries never rebuild hardware. */
public final class EcoNetWork extends MetaTileEntityAEHostablePart implements
        IMultiblockAbilityPart<EcoNetWork>, ICraftingCpuProvider, ICraftingProvider, IStorageProvider, IAEPowerStorage {
    private static final Map<EcoController.Kind, MultiblockAbility<EcoNetWork>> ABILITIES =
            new EnumMap<>(EcoController.Kind.class);
    public final EcoController.Kind kind;
    private boolean attached;
    public EcoNetWork(ResourceLocation id, EcoController.Kind kind) { super(id, 5, false); this.kind = kind; }
    public static MultiblockAbility<EcoNetWork> ability(EcoController.Kind kind) {
        return ABILITIES.computeIfAbsent(kind, k -> new MultiblockAbility<>("eco_" + k.id + "_network", EcoNetWork.class));
    }
    @Override public MetaTileEntity createMetaTileEntity(IGregTechTileEntity holder) {
        return new EcoNetWork(metaTileEntityId, kind);
    }
    public EcoController controller() {
        return getController() instanceof EcoController c && c.kind == kind && c.isStructureFormed() ? c : null;
    }
    public IActionSource source() { return getActionSource(); }
    @Override public MultiblockAbility<EcoNetWork> getAbility() { return ability(kind); }
    @Override public List<MultiblockAbility<?>> getAbilities() { return List.of(ability(kind)); }
    @Override public void registerAbilities(AbilityInstances instances) { if (instances.isKey(ability(kind))) instances.add(this); }
    @Override public IManagedGridNode getMainNode() {
        var node = super.getMainNode();
        if (!attached && kind != null) {
            if (kind == EcoController.Kind.STORAGE) {
                node.addService(IStorageProvider.class, this);
                node.addService(IAEPowerStorage.class, this);
            }
            attached = true;
        }
        return node;
    }
    @Override public void destroyMainNode() { super.destroyMainNode(); attached = false; }
    public void refreshServices() {
        if (getWorld() == null || getWorld().isRemote) return;
        var node = getMainNode();
        EcoController c = controller();
        double drain = kind == EcoController.Kind.COMPUTER
                ? ConfigHolder.compat.ae2.meHatchEnergyUsage : 64;
        if (c != null && kind == EcoController.Kind.STORAGE) for (var cell : c.cells()) drain += cell.getIdleDrain();
        node.setIdlePowerUsage(drain);
        if (kind == EcoController.Kind.STORAGE) {
            IStorageProvider.requestUpdate(node);
            if (node.getNode() != null) node.getNode().grid().postEvent(new GridPowerStorageChanged(this,
                    GridPowerStorageChanged.ChangeType.VALUES_CHANGED));
        }
        if (kind == EcoController.Kind.ASSEMBLER) ICraftingProvider.requestUpdate(node);
        if (kind == EcoController.Kind.COMPUTER && node.getNode() != null)
            node.getNode().grid().postEvent(new GridCraftingCpuChange(node.getNode()));
    }
    @Override public void onMainNodeStateChanged(IGridNodeListener.State state) {
        super.onMainNodeStateChanged(state);
        if (controller() != null) controller().hardwareChanged();
    }
    @Override public void addToMultiBlock(MultiblockControllerBase controller) {
        super.addToMultiBlock(controller);
        if (controller instanceof EcoController c) c.hardwareChanged();
    }
    @Override public void removeFromMultiBlock(MultiblockControllerBase controller) {
        super.removeFromMultiBlock(controller); refreshServices();
    }
    @Override public Collection<EcoCpu> getProvidedCraftingCpus() {
        EcoController c = controller();
        return c != null && kind == EcoController.Kind.COMPUTER && c.enabled() ? c.cpus() : List.of();
    }
    @Override public void mountInventories(IStorageMounts mounts) {
        EcoController c = controller();
        if (c != null && c.enabled() && kind == EcoController.Kind.STORAGE) for (var cell : c.cells()) mounts.mount(cell);
    }
    @Override public List<? extends IPatternDetails> getAvailablePatterns() {
        EcoController c = controller();
        return c != null && c.enabled() && kind == EcoController.Kind.ASSEMBLER ? c.patterns() : List.of();
    }
    @Override public boolean pushPattern(IPatternDetails pattern, KeyCounter[] inputs, int multiplier) {
        EcoController c = controller();
        if (c == null || !c.online() || !c.hasPattern(pattern)) return false;
        EcoPart worker = c.freeLane(multiplier);
        return worker != null && worker.lane.accept(pattern, inputs, multiplier);
    }
    @Override public boolean canMergePatternPush(IPatternDetails pattern) {
        EcoController c = controller();
        return c != null && c.online() && c.hasPattern(pattern)
                && pattern instanceof IAssemblerPattern;
    }
    @Override public int getMaxPatternPushMultiplier(IPatternDetails pattern, int maximum) {
        EcoController c = controller();
        if (c == null || !c.online() || !c.hasPattern(pattern)) return 0;
        int max = 0;
        for (EcoPart worker : c.parts(EcoPart.Role.WORKER))
            if (worker.lane.canAccept()) max = Math.max(max, EcoConfig.batch(worker.hardwareTier));
        return Math.min(Math.max(0, maximum), max);
    }
    @Override public boolean isBusy() {
        EcoController c = controller();
        return c == null || !c.online() || kind != EcoController.Kind.ASSEMBLER
                || c.parts(EcoPart.Role.WORKER).stream().noneMatch(p -> p.lane.canAccept());
    }
    @Override public double getAEMaxPower() {
        EcoController c = controller();
        return c == null ? 0 : c.parts(EcoPart.Role.ENERGY).stream().mapToDouble(p -> EcoTiers.energy(p.hardwareTier)).sum();
    }
    @Override public double getAECurrentPower() {
        EcoController c = controller();
        return c == null ? 0 : c.parts(EcoPart.Role.ENERGY).stream().mapToDouble(EcoPart::storedEnergy).sum();
    }
    @Override public double injectAEPower(double amount, Actionable mode) {
        return amount - transfer(amount, true, mode == Actionable.SIMULATE);
    }
    @Override public double extractAEPower(double amount, Actionable mode, PowerMultiplier multiplier) {
        return multiplier.divide(transfer(multiplier.multiply(amount), false, mode == Actionable.SIMULATE));
    }
    private double transfer(double amount, boolean inject, boolean simulate) {
        if (!Double.isFinite(amount) || amount <= 0) return 0;
        EcoController c = controller();
        if (c == null) return 0;
        double moved = 0;
        for (EcoPart part : c.parts(EcoPart.Role.ENERGY)) {
            moved += part.transferEnergy(amount - moved, inject, simulate);
            if (moved >= amount) break;
        }
        return moved;
    }
    @Override public boolean isAEPublicPowerStorage() { return true; }
    @Override public AccessRestriction getPowerFlow() { return AccessRestriction.READ_WRITE; }
    @Override public ICubeRenderer getBaseTexture() {
        return Textures.COMPUTER_CASING;
    }
    @Override public void renderMetaTileEntity(CCRenderState state,
            Matrix4 translation, IVertexOperation[] pipeline) {
        super.renderMetaTileEntity(state, translation, pipeline);
        Textures.OPTICAL_DATA_ACCESS_HATCH.renderSided(getFrontFacing(), state, translation, pipeline);
    }
    @SideOnly(Side.CLIENT)
    @Override public void addInformation(ItemStack stack, World world,
            List<String> tooltip, boolean advanced) {
        super.addInformation(stack, world, tooltip, advanced);
        tooltip.add(I18n.format("applygray.eco.tooltip.interface"));
    }
    @Override public boolean onRightClick(EntityPlayer player, EnumHand hand, EnumFacing side, CuboidRayTraceResult hit) {
        EcoGuiHandler.open(player, controller() == null ? this : controller()); return true;
    }
}
