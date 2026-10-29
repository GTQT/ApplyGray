package applygray.common.metatileentities.multi.multiblockpart.eco;

import gregtech.api.metatileentity.MetaTileEntity;
import gregtech.api.metatileentity.interfaces.IGregTechTileEntity;
import gregtech.api.metatileentity.multiblock.AbilityInstances;
import gregtech.api.metatileentity.multiblock.IMultiblockAbilityPart;
import gregtech.api.metatileentity.multiblock.MultiblockAbility;
import gregtech.api.metatileentity.multiblock.MultiblockControllerBase;
import gregtech.client.renderer.ICubeRenderer;
import gregtech.client.renderer.texture.Textures;
import gregtech.common.ConfigHolder;
import gregtech.common.metatileentities.multi.multiblockpart.appeng.MetaTileEntityAEHostablePart;

import applygray.common.metatileentities.multi.electric.eco.EcoConfig;
import applygray.common.metatileentities.multi.electric.eco.EcoControllerType;
import applygray.common.metatileentities.multi.electric.eco.MetaTileEntityEcoComputer;
import applygray.common.metatileentities.multi.electric.eco.MetaTileEntityEcoController;
import applygray.common.metatileentities.multi.electric.eco.MetaTileEntityEcoEnergyStorage;
import applygray.common.metatileentities.multi.electric.eco.MetaTileEntityEcoMolecularAssembler;
import applygray.common.metatileentities.multi.electric.eco.MetaTileEntityEcoStorage;
import applygray.common.metatileentities.multi.electric.eco.gui.EcoGuiHandler;
import applygray.common.metatileentities.multi.electric.eco.logic.EcoCpu;

import ae2.api.config.AccessRestriction;
import ae2.api.config.Actionable;
import ae2.api.config.PowerMultiplier;
import ae2.api.crafting.IAssemblerPattern;
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

import net.minecraft.client.resources.I18n;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import codechicken.lib.raytracer.CuboidRayTraceResult;
import codechicken.lib.render.CCRenderState;
import codechicken.lib.render.pipeline.IVertexOperation;
import codechicken.lib.vec.Matrix4;

import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** ECO 的 ME 接口：按控制器类型只暴露对应服务（CPU / 存储 / 样板 / 能量）。 */
public class MetaTileEntityEcoNetworkPart extends MetaTileEntityAEHostablePart implements
        IMultiblockAbilityPart<MetaTileEntityEcoNetworkPart>, ICraftingCpuProvider, ICraftingProvider,
        IStorageProvider, IAEPowerStorage {

    private static final Map<EcoControllerType, MultiblockAbility<MetaTileEntityEcoNetworkPart>> ABILITIES =
            new EnumMap<>(EcoControllerType.class);

    public final EcoControllerType type;
    private boolean attached;

    public MetaTileEntityEcoNetworkPart(ResourceLocation metaTileEntityId, EcoControllerType type) {
        super(metaTileEntityId, 5, false);
        this.type = type;
    }

    public static MultiblockAbility<MetaTileEntityEcoNetworkPart> ability(EcoControllerType type) {
        return ABILITIES.computeIfAbsent(type, ignored -> new MultiblockAbility<>(
                "eco_" + type.id + "_network", MetaTileEntityEcoNetworkPart.class));
    }

    @Override
    public MetaTileEntity createMetaTileEntity(IGregTechTileEntity holder) {
        return new MetaTileEntityEcoNetworkPart(metaTileEntityId, type);
    }

    public MetaTileEntityEcoController controller() {
        return getController() instanceof MetaTileEntityEcoController controller
                && controller.type() == type && controller.isStructureFormed() ? controller : null;
    }

    public IActionSource source() {
        return getActionSource();
    }

    @Override
    public MultiblockAbility<MetaTileEntityEcoNetworkPart> getAbility() {
        return ability(type);
    }

    @Override
    public List<MultiblockAbility<?>> getAbilities() {
        return List.of(ability(type));
    }

    @Override
    public void registerAbilities(AbilityInstances instances) {
        if (instances.isKey(ability(type))) instances.add(this);
    }

    @Override
    public IManagedGridNode getMainNode() {
        var node = super.getMainNode();
        if (!attached) {
            // 服务按组分拆分：存储只挂存储，电池只挂能量。
            if (type == EcoControllerType.STORAGE) node.addService(IStorageProvider.class, this);
            if (type == EcoControllerType.BATTERY) node.addService(IAEPowerStorage.class, this);
            attached = true;
        }
        return node;
    }

    @Override
    public void destroyMainNode() {
        super.destroyMainNode();
        attached = false;
    }

    public void refreshServices() {
        if (getWorld() == null || getWorld().isRemote) return;
        var node = getMainNode();
        MetaTileEntityEcoController controller = controller();
        double drain = switch (type) {
            case COMPUTER -> ConfigHolder.compat.ae2.meHatchEnergyUsage;
            case STORAGE -> {
                double value = 64;
                if (controller instanceof MetaTileEntityEcoStorage storage) {
                    for (var cell : storage.cells()) value += cell.getIdleDrain();
                }
                yield value;
            }
            case ASSEMBLER -> 64;
            case BATTERY -> 0;
        };
        node.setIdlePowerUsage(drain);
        if (type == EcoControllerType.STORAGE) {
            IStorageProvider.requestUpdate(node);
        }
        if (type == EcoControllerType.ASSEMBLER) ICraftingProvider.requestUpdate(node);
        if (type == EcoControllerType.BATTERY && node.getNode() != null) {
            node.getNode().grid().postEvent(new GridPowerStorageChanged(this,
                    GridPowerStorageChanged.ChangeType.VALUES_CHANGED));
        }
        if (type == EcoControllerType.COMPUTER && node.getNode() != null) {
            node.getNode().grid().postEvent(new GridCraftingCpuChange(node.getNode()));
        }
    }

    @Override
    public void onMainNodeStateChanged(IGridNodeListener.State state) {
        super.onMainNodeStateChanged(state);
        if (controller() != null) controller().hardwareChanged();
    }

    @Override
    public void addToMultiBlock(MultiblockControllerBase controller) {
        super.addToMultiBlock(controller);
        if (controller instanceof MetaTileEntityEcoController eco) eco.hardwareChanged();
    }

    @Override
    public void removeFromMultiBlock(MultiblockControllerBase controller) {
        super.removeFromMultiBlock(controller);
        refreshServices();
    }

    @Override
    public void onUnload() {
        MetaTileEntityEcoController controller = controller();
        if (getWorld() != null && !getWorld().isRemote && controller != null) controller.invalidateStructure();
        super.onUnload();
    }

    @Override
    public Collection<EcoCpu> getProvidedCraftingCpus() {
        return controller() instanceof MetaTileEntityEcoComputer computer && computer.enabled()
                ? computer.cpus() : List.of();
    }

    @Override
    public void mountInventories(IStorageMounts mounts) {
        if (controller() instanceof MetaTileEntityEcoStorage storage && storage.enabled()) {
            for (var cell : storage.cells()) mounts.mount(cell);
        }
    }

    @Override
    public List<? extends IPatternDetails> getAvailablePatterns() {
        return controller() instanceof MetaTileEntityEcoMolecularAssembler assembler && assembler.enabled()
                ? assembler.patterns() : List.of();
    }

    @Override
    public boolean pushPattern(IPatternDetails pattern, KeyCounter[] inputs, int multiplier) {
        MetaTileEntityEcoController controller = controller();
        if (!(controller instanceof MetaTileEntityEcoMolecularAssembler assembler) || !assembler.online()
                || !assembler.hasPattern(pattern)) {
            return false;
        }
        MetaTileEntityEcoWorkerPart worker = assembler.freeLane(multiplier);
        return worker != null && worker.lane().accept(pattern, inputs, multiplier);
    }

    @Override
    public boolean canMergePatternPush(IPatternDetails pattern) {
        MetaTileEntityEcoController controller = controller();
        return controller instanceof MetaTileEntityEcoMolecularAssembler assembler && assembler.online()
                && assembler.hasPattern(pattern) && pattern instanceof IAssemblerPattern;
    }

    @Override
    public int getMaxPatternPushMultiplier(IPatternDetails pattern, int maximum) {
        MetaTileEntityEcoController controller = controller();
        if (!(controller instanceof MetaTileEntityEcoMolecularAssembler assembler) || !assembler.online()
                || !assembler.hasPattern(pattern)) {
            return 0;
        }
        int max = 0;
        for (MetaTileEntityEcoWorkerPart worker : assembler.parts(EcoPartRole.WORKER,
                MetaTileEntityEcoWorkerPart.class)) {
            if (worker.lane().canAccept()) max = Math.max(max, EcoConfig.batch(worker.hardwareTier));
        }
        return Math.min(Math.max(0, maximum), max);
    }

    @Override
    public boolean isBusy() {
        MetaTileEntityEcoController controller = controller();
        return !(controller instanceof MetaTileEntityEcoMolecularAssembler assembler) || !assembler.online()
                || assembler.parts(EcoPartRole.WORKER, MetaTileEntityEcoWorkerPart.class).stream()
                .noneMatch(worker -> worker.lane().canAccept());
    }

    @Override
    public double getAEMaxPower() {
        return controller() instanceof MetaTileEntityEcoEnergyStorage battery ? battery.energyCapacity() : 0;
    }

    @Override
    public double getAECurrentPower() {
        return controller() instanceof MetaTileEntityEcoEnergyStorage battery ? battery.storedEnergy() : 0;
    }

    @Override
    public double injectAEPower(double amount, Actionable mode) {
        if (!(controller() instanceof MetaTileEntityEcoEnergyStorage)) return amount;
        return amount - transfer(amount, true, mode == Actionable.SIMULATE);
    }

    @Override
    public double extractAEPower(double amount, Actionable mode, PowerMultiplier multiplier) {
        if (!(controller() instanceof MetaTileEntityEcoEnergyStorage)) return 0;
        return multiplier.divide(transfer(multiplier.multiply(amount), false, mode == Actionable.SIMULATE));
    }

    private double transfer(double amount, boolean inject, boolean simulate) {
        if (!(controller() instanceof MetaTileEntityEcoEnergyStorage battery)
                || !Double.isFinite(amount) || amount <= 0) {
            return 0;
        }
        double moved = 0;
        for (MetaTileEntityEcoEnergyPart part : battery.parts(EcoPartRole.ENERGY,
                MetaTileEntityEcoEnergyPart.class)) {
            moved += part.transferEnergy(amount - moved, inject, simulate);
            if (moved >= amount) break;
        }
        return moved;
    }

    @Override
    public boolean isAEPublicPowerStorage() {
        return type == EcoControllerType.BATTERY;
    }

    @Override
    public AccessRestriction getPowerFlow() {
        return AccessRestriction.READ_WRITE;
    }

    @Override
    public ICubeRenderer getBaseTexture() {
        // 与 ECO 仓室一致：成型后交给父类按 controller.getCasingBlock(this) 生成 VisualStateRenderer。
        if (getController() == null) return Textures.COMPUTER_CASING;
        return super.getBaseTexture();
    }

    @Override
    public void renderMetaTileEntity(CCRenderState state, Matrix4 translation, IVertexOperation[] pipeline) {
        super.renderMetaTileEntity(state, translation, pipeline);
        Textures.OPTICAL_DATA_ACCESS_HATCH.renderSided(getFrontFacing(), state, translation, pipeline);
    }

    @SideOnly(Side.CLIENT)
    @Override
    public void addInformation(ItemStack stack, World world, List<String> tooltip, boolean advanced) {
        super.addInformation(stack, world, tooltip, advanced);
        tooltip.add(I18n.format("applygray.eco.tooltip.interface"));
    }

    @Override
    public boolean onRightClick(EntityPlayer player, EnumHand hand, EnumFacing side, CuboidRayTraceResult hit) {
        EcoGuiHandler.open(player, controller() == null ? this : controller());
        return true;
    }
}
