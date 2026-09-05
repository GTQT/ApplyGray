package applygray.common.metatileentities.multiblock.storage;

import applygray.integration.ae2.ApplyGrayGridNodeSupport;

import gregtech.api.GTValues;
import gregtech.api.metatileentity.MetaTileEntity;
import gregtech.api.metatileentity.interfaces.IGregTechTileEntity;
import gregtech.api.metatileentity.multiblock.AbilityInstances;
import gregtech.api.metatileentity.multiblock.IMultiblockAbilityPart;
import gregtech.api.metatileentity.multiblock.MultiblockAbility;
import gregtech.common.metatileentities.multi.multiblockpart.appeng.MetaTileEntityAEHostablePart;

import net.minecraft.client.resources.I18n;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import ae2.api.networking.IGridNodeListener;
import ae2.api.networking.IManagedGridNode;
import ae2.api.networking.security.IActionSource;
import ae2.api.stacks.AEFluidKey;
import ae2.api.stacks.AEItemKey;
import ae2.api.stacks.AEKey;
import ae2.api.stacks.KeyCounter;
import ae2.api.storage.IStorageMounts;
import ae2.api.storage.IStorageProvider;
import ae2.api.storage.MEStorage;

import java.math.BigInteger;
import java.util.List;

/**
 * Quantum Access Hatch: a multiblock part that joins a formed quantum item or
 * fluid storage array and mounts its whole content store onto the attached ME
 * network as a storage provider. The network side only sees the channel that
 * matches the controller type (items for a chest, fluids for a tank).
 */
public class MetaTileEntityQuantumAccessHatch extends MetaTileEntityAEHostablePart
        implements IMultiblockAbilityPart<MetaTileEntityQuantumAccessHatch>, IStorageProvider {

    public static final MultiblockAbility<MetaTileEntityQuantumAccessHatch> QUANTUM_ACCESS =
            new MultiblockAbility<>("quantum_access", MetaTileEntityQuantumAccessHatch.class);

    private static final long UPDATE_INTERVAL = 20;

    private boolean storageServiceAttached;
    private long lastSyncStamp = -1;
    private MetaTileEntityQuantumItemStorage lastItemController;
    private MetaTileEntityQuantumFluidStorage lastFluidController;

    public MetaTileEntityQuantumAccessHatch(ResourceLocation metaTileEntityId) {
        super(metaTileEntityId, GTValues.LuV, false);
    }

    @Override
    public MetaTileEntity createMetaTileEntity(IGregTechTileEntity tileEntity) {
        return new MetaTileEntityQuantumAccessHatch(metaTileEntityId);
    }

    // ------------------------------------------------------------------
    // Multiblock ability wiring: this part fills the 'A' casing slots of the
    // quantum storage controllers.
    // ------------------------------------------------------------------

    @Override
    public MultiblockAbility<MetaTileEntityQuantumAccessHatch> getAbility() {
        return QUANTUM_ACCESS;
    }

    @Override
    public List<MultiblockAbility<?>> getAbilities() {
        return List.of(QUANTUM_ACCESS);
    }

    @Override
    public void registerAbilities(@NotNull AbilityInstances abilityInstances) {
        if (abilityInstances.isKey(QUANTUM_ACCESS)) {
            abilityInstances.add(this);
        }
    }

    // ------------------------------------------------------------------
    // ME node: storage provider service mounted on the AE hostable part node.
    // ------------------------------------------------------------------

    @Override
    public @NotNull IManagedGridNode getMainNode() {
        IManagedGridNode node = super.getMainNode();
        if (!storageServiceAttached) {
            node.addService(IStorageProvider.class, this);
            storageServiceAttached = true;
        }
        return node;
    }

    @Override
    public void mountInventories(IStorageMounts mounts) {
        MetaTileEntityQuantumItemStorage itemController = resolveItemController();
        MetaTileEntityQuantumFluidStorage fluidController = resolveFluidController();
        if (itemController != null) {
            mounts.mount(new ItemStorageView(itemController));
        } else if (fluidController != null) {
            mounts.mount(new FluidStorageView(fluidController));
        }
    }

    @Override
    public void update() {
        super.update();
        if (getWorld() == null || getWorld().isRemote) {
            return;
        }
        if (getOffsetTimer() % UPDATE_INTERVAL != 0) {
            return;
        }
        MetaTileEntityQuantumItemStorage itemController = resolveItemController();
        MetaTileEntityQuantumFluidStorage fluidController = resolveFluidController();
        if (itemController == null && fluidController == null) {
            lastSyncStamp = -1;
            return;
        }
        long stamp = itemController != null
                ? controllerStamp(itemController)
                : controllerStamp(fluidController);
        if (stamp == lastSyncStamp
                && lastItemController == itemController
                && lastFluidController == fluidController) {
            return;
        }
        lastSyncStamp = stamp;
        lastItemController = itemController;
        lastFluidController = fluidController;
        // Content or controller changed: have the grid re-mount this provider.
        IStorageProvider.requestUpdate(getMainNode());
    }

    private static long controllerStamp(MetaTileEntityQuantumItemStorage controller) {
        return controller.itemStorage().distinctSlots()
                ^ controller.itemStorage().totalStored().longValue();
    }

    private static long controllerStamp(MetaTileEntityQuantumFluidStorage controller) {
        return controller.fluidStorage().distinctSlots()
                ^ controller.fluidStorage().totalStored().longValue();
    }

    @Nullable
    private MetaTileEntityQuantumItemStorage resolveItemController() {
        if (getController() instanceof MetaTileEntityQuantumItemStorage controller && controller.isStructureFormed()) {
            return controller;
        }
        return null;
    }

    @Nullable
    private MetaTileEntityQuantumFluidStorage resolveFluidController() {
        if (getController() instanceof MetaTileEntityQuantumFluidStorage controller && controller.isStructureFormed()) {
            return controller;
        }
        return null;
    }

    @Override
    public void onMainNodeStateChanged(IGridNodeListener.State state) {
        super.onMainNodeStateChanged(state);
        // Whenever the node (re)boots, let the grid re-mount this provider.
        if (state == IGridNodeListener.State.GRID_BOOT) {
            IStorageProvider.requestUpdate(getMainNode());
        }
    }

    // ------------------------------------------------------------------
    // MEStorage views over the two controller flavours.
    // ------------------------------------------------------------------

    private static final class ItemStorageView implements MEStorage {

        private final MetaTileEntityQuantumItemStorage controller;

        private ItemStorageView(MetaTileEntityQuantumItemStorage controller) {
            this.controller = controller;
        }

        @Override
        public long insert(AEKey key, long amount, ae2.api.config.Actionable action,
                           IActionSource source) {
            if (!AEItemKey.is(key)) {
                return 0;
            }
            ItemStack stack = ((AEItemKey) key).toStack(1);
            if (stack.isEmpty()) {
                return 0;
            }
            BigInteger accepted = controller.insertItemStack(stack, BigInteger.valueOf(amount),
                    action.isSimulate());
            return clampLong(accepted);
        }

        @Override
        public long extract(AEKey key, long amount, ae2.api.config.Actionable action,
                            IActionSource source) {
            if (!AEItemKey.is(key)) {
                return 0;
            }
            ItemStack stack = ((AEItemKey) key).toStack(1);
            if (stack.isEmpty()) {
                return 0;
            }
            BigInteger removed = controller.extractItemStack(stack, BigInteger.valueOf(amount),
                    action.isSimulate());
            return clampLong(removed);
        }

        @Override
        public void getAvailableStacks(KeyCounter out) {
            for (var entry : controller.itemStorage().entries()) {
                ItemStack stack = entry.getKey();
                if (!stack.isEmpty()) {
                    out.add(AEItemKey.of(stack), clampLong(entry.getValue()));
                }
            }
        }

        @Override
        public ITextComponent getDescription() {
            return new TextComponentTranslation("applygray.machine.quantum_storage.chest.name");
        }
    }

    private static final class FluidStorageView implements MEStorage {

        private final MetaTileEntityQuantumFluidStorage controller;

        private FluidStorageView(MetaTileEntityQuantumFluidStorage controller) {
            this.controller = controller;
        }

        @Override
        public long insert(AEKey key, long amount, ae2.api.config.Actionable action,
                           IActionSource source) {
            if (!AEFluidKey.is(key)) {
                return 0;
            }
            var fluid = ((AEFluidKey) key).toStack(1);
            if (fluid == null) {
                return 0;
            }
            BigInteger accepted = controller.insertFluid(fluid, BigInteger.valueOf(amount),
                    action.isSimulate());
            return clampLong(accepted);
        }

        @Override
        public long extract(AEKey key, long amount, ae2.api.config.Actionable action,
                            IActionSource source) {
            if (!AEFluidKey.is(key)) {
                return 0;
            }
            var fluid = ((AEFluidKey) key).toStack(1);
            if (fluid == null) {
                return 0;
            }
            BigInteger removed = controller.extractFluid(fluid, BigInteger.valueOf(amount),
                    action.isSimulate());
            return clampLong(removed);
        }

        @Override
        public void getAvailableStacks(KeyCounter out) {
            for (var entry : controller.fluidStorage().entries()) {
                var fluid = entry.getKey();
                if (fluid != null) {
                    out.add(AEFluidKey.of(fluid), clampLong(entry.getValue()));
                }
            }
        }

        @Override
        public ITextComponent getDescription() {
            return new TextComponentTranslation("applygray.machine.quantum_storage.tank.name");
        }
    }

    private static long clampLong(BigInteger value) {
        if (value.signum() <= 0) {
            return 0;
        }
        return value.min(BigInteger.valueOf(Long.MAX_VALUE)).longValue();
    }

    // ------------------------------------------------------------------
    // Misc.
    // ------------------------------------------------------------------

    @SideOnly(Side.CLIENT)
    @Override
    public void addInformation(ItemStack stack, @Nullable World world, @NotNull List<String> tooltip,
                               boolean advanced) {
        super.addInformation(stack, world, tooltip, advanced);
        tooltip.add(I18n.format("applygray.machine.quantum_access_hatch.tooltip.1"));
        tooltip.add(I18n.format("applygray.machine.quantum_access_hatch.tooltip.2"));
        tooltip.add(I18n.format("applygray.machine.quantum_access_hatch.tooltip.3"));
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound data) {
        super.writeToNBT(data);
        return data;
    }
}
