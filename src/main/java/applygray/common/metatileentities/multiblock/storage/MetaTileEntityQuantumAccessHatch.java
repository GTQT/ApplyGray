package applygray.common.metatileentities.multiblock.storage;

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
import ae2.api.storage.MEStorageChangeListener;
import ae2.api.storage.MEStorageMonitor;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

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
    private ItemStorageView mountedItemView;
    private FluidStorageView mountedFluidView;

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
        // The mounted monitors are retained so content changes can be pushed as exact deltas instead of
        // forcing the grid to re-enumerate this provider.
        mountedItemView = null;
        mountedFluidView = null;
        lastItemController = itemController;
        lastFluidController = fluidController;
        if (itemController != null) {
            mountedItemView = new ItemStorageView(itemController);
            mounts.mount(mountedItemView);
        } else if (fluidController != null) {
            mountedFluidView = new FluidStorageView(fluidController);
            mounts.mount(mountedFluidView);
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
            lastItemController = null;
            lastFluidController = null;
            return;
        }
        // Only a different controller (formed, reformed or swapped) needs the grid to re-mount this provider.
        if (lastItemController != itemController || lastFluidController != fluidController) {
            lastItemController = itemController;
            lastFluidController = fluidController;
            lastSyncStamp = -1;
            IStorageProvider.requestUpdate(getMainNode());
            return;
        }
        long stamp = itemController != null
                ? controllerStamp(itemController)
                : controllerStamp(fluidController);
        if (stamp == lastSyncStamp) {
            return;
        }
        lastSyncStamp = stamp;
        // Content changed outside the mounted views: publish deltas rather than re-enumerating on the grid.
        if (mountedItemView != null) {
            mountedItemView.publishChanges();
        } else if (mountedFluidView != null) {
            mountedFluidView.publishChanges();
        }
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
    // MEStorageMonitor views over the two controller flavours.
    //
    // The grid enumerates a mounted monitor once and then relies on exact signed deltas, so these views keep the
    // last reported content as a baseline and diff against it whenever the controller changes.
    // ------------------------------------------------------------------

    private abstract static class ControllerStorageView implements MEStorageMonitor {

        private final List<ListenerRegistration> listeners = new ArrayList<>();
        private final KeyCounter publishedSnapshot = new KeyCounter();
        private boolean snapshotInitialized;
        private boolean publishing;
        private boolean publishRequested;

        /** Writes the wrapped controller's current content into {@code out}. */
        abstract void collectAvailableStacks(KeyCounter out);

        @Override
        public final void addListener(MEStorageChangeListener listener, Object verificationToken) {
            Objects.requireNonNull(listener, "listener");
            for (int i = 0; i < listeners.size(); i++) {
                if (listeners.get(i).listener() == listener) {
                    throw new IllegalStateException("The storage listener is already registered.");
                }
            }
            listeners.add(new ListenerRegistration(listener, verificationToken));
        }

        @Override
        public final void removeListener(MEStorageChangeListener listener) {
            for (int i = listeners.size() - 1; i >= 0; i--) {
                if (listeners.get(i).listener() == listener) {
                    listeners.remove(i);
                }
            }
        }

        @Override
        public final void getAvailableStacks(KeyCounter out) {
            KeyCounter current = collect();
            for (var entry : current) {
                out.add(entry.getKey(), entry.getLongValue());
            }
            // The enumeration defines the baseline: only changes after it are published as deltas.
            syncSnapshot(current);
            removeInvalidListeners();
        }

        /** Publishes one exact signed delta per key that changed since the last reported content. */
        final void publishChanges() {
            if (listeners.isEmpty()) {
                return;
            }
            if (publishing) {
                // A listener changed the controller re-entrantly; re-diff once the current pass finishes.
                publishRequested = true;
                return;
            }
            publishing = true;
            try {
                do {
                    publishRequested = false;
                    publishOnce();
                } while (publishRequested);
            } finally {
                publishing = false;
            }
        }

        private void publishOnce() {
            KeyCounter current = collect();
            if (!snapshotInitialized) {
                syncSnapshot(current);
                return;
            }
            for (var entry : publishedSnapshot) {
                long delta = current.get(entry.getKey()) - entry.getLongValue();
                if (delta != 0) {
                    notifyStackChange(entry.getKey(), delta);
                }
            }
            for (var entry : current) {
                if (publishedSnapshot.get(entry.getKey()) == 0 && entry.getLongValue() != 0) {
                    notifyStackChange(entry.getKey(), entry.getLongValue());
                }
            }
            syncSnapshot(current);
        }

        private KeyCounter collect() {
            KeyCounter counter = new KeyCounter();
            collectAvailableStacks(counter);
            counter.removeZeros();
            return counter;
        }

        private void syncSnapshot(KeyCounter current) {
            publishedSnapshot.clear();
            for (var entry : current) {
                publishedSnapshot.add(entry.getKey(), entry.getLongValue());
            }
            snapshotInitialized = true;
        }

        private void notifyStackChange(AEKey key, long delta) {
            for (int i = listeners.size() - 1; i >= 0; i--) {
                ListenerRegistration registration = listeners.get(i);
                if (!registration.listener().isValid(registration.verificationToken())) {
                    listeners.remove(i);
                    continue;
                }
                registration.listener().onStackChange(key, delta);
            }
        }

        private void removeInvalidListeners() {
            for (int i = listeners.size() - 1; i >= 0; i--) {
                ListenerRegistration registration = listeners.get(i);
                if (!registration.listener().isValid(registration.verificationToken())) {
                    listeners.remove(i);
                }
            }
        }

        private record ListenerRegistration(MEStorageChangeListener listener, Object verificationToken) {
        }
    }

    private static final class ItemStorageView extends ControllerStorageView {

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
            long inserted = clampLong(accepted);
            if (!action.isSimulate() && inserted > 0) {
                publishChanges();
            }
            return inserted;
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
            long extracted = clampLong(removed);
            if (!action.isSimulate() && extracted > 0) {
                publishChanges();
            }
            return extracted;
        }

        @Override
        void collectAvailableStacks(KeyCounter out) {
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

    private static final class FluidStorageView extends ControllerStorageView {

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
            long inserted = clampLong(accepted);
            if (!action.isSimulate() && inserted > 0) {
                publishChanges();
            }
            return inserted;
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
            long extracted = clampLong(removed);
            if (!action.isSimulate() && extracted > 0) {
                publishChanges();
            }
            return extracted;
        }

        @Override
        void collectAvailableStacks(KeyCounter out) {
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
