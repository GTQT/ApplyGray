package applygray.common.metatileentities.multiblock.storage;

import gregtech.api.GTValues;
import gregtech.api.metatileentity.MetaTileEntity;
import gregtech.api.metatileentity.interfaces.IGregTechTileEntity;
import gregtech.api.metatileentity.multiblock.AbilityInstances;
import gregtech.api.metatileentity.multiblock.IMultiblockAbilityPart;
import gregtech.api.metatileentity.multiblock.MultiblockAbility;
import gregtech.api.metatileentity.multiblock.MultiblockControllerBase;
import gregtech.common.metatileentities.multi.multiblockpart.appeng.MetaTileEntityAEHostablePart;

import net.minecraft.client.resources.I18n;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.World;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import ae2.api.config.Actionable;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;

/**
 * Quantum Access Hatch: a multiblock part that joins a formed quantum item or
 * fluid storage array and mounts its whole content store onto the attached ME
 * network as a storage provider. The network side only sees the channel that
 * matches the controller type (items for a chest, fluids for a tank).
 * <p>
 * Content changes are pushed: the controller's storage handler reports every
 * accepted insert and extraction to this part, which turns it into one exact
 * signed delta for the grid. The part therefore never polls the store, never
 * resends the whole list, and a single stack moving reaches AE in the tick it
 * moved instead of up to a second later. Only structural transitions (form,
 * break, controller swap, node boot) trigger a re-mount.
 */
public class MetaTileEntityQuantumAccessHatch extends MetaTileEntityAEHostablePart
        implements IMultiblockAbilityPart<MetaTileEntityQuantumAccessHatch>, IStorageProvider {

    public static final MultiblockAbility<MetaTileEntityQuantumAccessHatch> QUANTUM_ACCESS =
            new MultiblockAbility<>("quantum_access", MetaTileEntityQuantumAccessHatch.class);

    /**
     * The multiblock hooks drive every mounting transition; this periodic check only repairs a transition that did
     * not reach them, so it compares references and costs no more than a couple of field reads.
     */
    private static final long RECONCILE_INTERVAL = 20;

    private boolean storageServiceAttached;

    private MetaTileEntityQuantumItemStorage listeningItemController;
    private MetaTileEntityQuantumFluidStorage listeningFluidController;

    private MetaTileEntityQuantumItemStorage mountedItemController;
    private MetaTileEntityQuantumFluidStorage mountedFluidController;

    private ItemStorageView mountedItemView;
    private FluidStorageView mountedFluidView;

    private final BiConsumer<ItemStack, BigInteger> itemStorageListener = (stack, delta) -> {
        ItemStorageView view = mountedItemView;
        if (view != null) {
            view.storageChanged(stack, delta);
        }
    };

    private final BiConsumer<FluidStack, BigInteger> fluidStorageListener = (fluid, delta) -> {
        FluidStorageView view = mountedFluidView;
        if (view != null) {
            view.storageChanged(fluid, delta);
        }
    };

    public MetaTileEntityQuantumAccessHatch(ResourceLocation metaTileEntityId) {
        super(metaTileEntityId, GTValues.LuV, false);
    }

    @Override
    public MetaTileEntity createMetaTileEntity(IGregTechTileEntity tileEntity) {
        return new MetaTileEntityQuantumAccessHatch(metaTileEntityId);
    }

    // ------------------------------------------------------------------
    // Multiblock ability wiring: this part fills the 'C' casing slots of the
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
        // The views are retained so a storage change can be forwarded as an exact delta, and dropped as soon as the
        // grid re-mounts without a controller, which is how a broken multiblock leaves the network.
        mountedItemView = null;
        mountedFluidView = null;
        mountedItemController = itemController;
        mountedFluidController = fluidController;
        if (itemController != null) {
            mountedItemView = new ItemStorageView(itemController);
            mounts.mount(mountedItemView);
        } else if (fluidController != null) {
            mountedFluidView = new FluidStorageView(fluidController);
            mounts.mount(mountedFluidView);
        }
    }

    // ------------------------------------------------------------------
    // Lifecycle: the listener is bound exactly as long as the structure is formed.
    // ------------------------------------------------------------------

    @Override
    public void addToMultiBlock(MultiblockControllerBase controllerBase) {
        super.addToMultiBlock(controllerBase);
        if (getWorld() == null || getWorld().isRemote) {
            return;
        }
        unbindStorage();
        if (controllerBase instanceof MetaTileEntityQuantumItemStorage itemController) {
            listeningItemController = itemController;
            itemController.itemStorage().addListener(itemStorageListener);
        } else if (controllerBase instanceof MetaTileEntityQuantumFluidStorage fluidController) {
            listeningFluidController = fluidController;
            fluidController.fluidStorage().addListener(fluidStorageListener);
        }
        requestStorageUpdate();
    }

    @Override
    public void removeFromMultiBlock(MultiblockControllerBase controllerBase) {
        unbindStorage();
        super.removeFromMultiBlock(controllerBase);
        // Re-mounting without a controller is what removes this provider from the network.
        requestStorageUpdate();
    }

    @Override
    public void invalidate() {
        unbindStorage();
        super.invalidate();
    }

    @Override
    public void update() {
        super.update();
        if (getWorld() == null || getWorld().isRemote) {
            return;
        }
        if (getOffsetTimer() % RECONCILE_INTERVAL != 0) {
            return;
        }
        // Pushes make content polling unnecessary, so this only checks that the listener and the mounted views still
        // belong to the controller that is actually formed.
        MetaTileEntityQuantumItemStorage itemController = resolveItemController();
        MetaTileEntityQuantumFluidStorage fluidController = resolveFluidController();
        boolean listenersMatch = itemController == listeningItemController
                && fluidController == listeningFluidController;
        boolean mountsMatch = itemController == mountedItemController
                && fluidController == mountedFluidController;
        if (listenersMatch && mountsMatch) {
            return;
        }
        if (!listenersMatch) {
            unbindStorage();
            if (itemController != null) {
                listeningItemController = itemController;
                itemController.itemStorage().addListener(itemStorageListener);
            } else if (fluidController != null) {
                listeningFluidController = fluidController;
                fluidController.fluidStorage().addListener(fluidStorageListener);
            }
        }
        requestStorageUpdate();
    }

    private void unbindStorage() {
        if (listeningItemController != null) {
            listeningItemController.itemStorage().removeListener(itemStorageListener);
            listeningItemController = null;
        }
        if (listeningFluidController != null) {
            listeningFluidController.fluidStorage().removeListener(fluidStorageListener);
            listeningFluidController = null;
        }
        mountedItemView = null;
        mountedFluidView = null;
        mountedItemController = null;
        mountedFluidController = null;
    }

    private void requestStorageUpdate() {
        World world = getWorld();
        if (world == null || world.isRemote) {
            return;
        }
        IStorageProvider.requestUpdate(getMainNode());
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
            requestStorageUpdate();
        }
    }

    // ------------------------------------------------------------------
    // MEStorageMonitor views over the two controller flavours.
    //
    // The grid enumerates a mounted monitor once and then relies on exact signed deltas, so these views keep the
    // amounts they last reported as a baseline. Every change is pushed by the controller's storage handler, and the
    // delta is derived from the authoritative amount of that single type: O(1) per change, self-correcting when an
    // amount saturates the long range.
    // ------------------------------------------------------------------

    private abstract static class ControllerStorageView<T> implements MEStorageMonitor {

        private final List<ListenerRegistration> listeners = new CopyOnWriteArrayList<>();
        /** Amounts already reported to the grid, clamped to long; meaningful once a full enumeration ran. */
        private final Map<AEKey, Long> reported = new HashMap<>();
        private boolean baselineReady;

        /** Writes the wrapped controller's current content into {@code out}. */
        abstract void collectAvailableStacks(KeyCounter out);

        /** Maps one stored content type onto the AE key the grid uses for it. */
        @Nullable
        abstract AEKey aeKeyOf(T type);

        /** Authoritative amount of one content type, read in constant time from the store. */
        abstract BigInteger currentAmount(T type);

        @Override
        public final void addListener(MEStorageChangeListener listener, Object verificationToken) {
            Objects.requireNonNull(listener, "listener");
            for (ListenerRegistration registration : listeners) {
                if (registration.listener() == listener) {
                    throw new IllegalStateException("The storage listener is already registered.");
                }
            }
            listeners.add(new ListenerRegistration(listener, verificationToken));
        }

        @Override
        public final void removeListener(MEStorageChangeListener listener) {
            listeners.removeIf(registration -> registration.listener() == listener);
        }

        @Override
        public final void getAvailableStacks(KeyCounter out) {
            KeyCounter current = collect();
            for (var entry : current) {
                out.add(entry.getKey(), entry.getLongValue());
            }
            // The enumeration defines the baseline: only changes after it are published as deltas.
            reported.clear();
            for (var entry : current) {
                reported.put(entry.getKey(), entry.getLongValue());
            }
            baselineReady = true;
            removeInvalidListeners();
        }

        /** Publishes the exact delta of one changed type, once the grid knows this store. */
        final void storageChanged(T type, BigInteger delta) {
            if (delta.signum() == 0 || !baselineReady || listeners.isEmpty()) {
                return;
            }
            AEKey key = aeKeyOf(type);
            if (key == null) {
                return;
            }
            long previous = reported.getOrDefault(key, 0L);
            long current = clampLong(currentAmount(type));
            if (current == previous) {
                return;
            }
            if (current == 0L) {
                reported.remove(key);
            } else {
                reported.put(key, current);
            }
            notifyStackChange(key, current - previous);
        }

        private KeyCounter collect() {
            KeyCounter counter = new KeyCounter();
            collectAvailableStacks(counter);
            counter.removeZeros();
            return counter;
        }

        private void notifyStackChange(AEKey key, long delta) {
            for (ListenerRegistration registration : listeners) {
                // A listener may unregister itself from inside the callback, but the copy-on-write snapshot still
                // holds it, and removal must end the callbacks immediately.
                if (!listeners.contains(registration)) {
                    continue;
                }
                if (!registration.listener().isValid(registration.verificationToken())) {
                    listeners.remove(registration);
                    continue;
                }
                registration.listener().onStackChange(key, delta);
            }
        }

        private void removeInvalidListeners() {
            listeners.removeIf(registration -> !registration.listener().isValid(registration.verificationToken()));
        }

        private record ListenerRegistration(MEStorageChangeListener listener, Object verificationToken) {
        }
    }

    private static final class ItemStorageView extends ControllerStorageView<ItemStack> {

        private final MetaTileEntityQuantumItemStorage controller;

        private ItemStorageView(MetaTileEntityQuantumItemStorage controller) {
            this.controller = controller;
        }

        @Override
        public long insert(AEKey key, long amount, Actionable action, IActionSource source) {
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
        public long extract(AEKey key, long amount, Actionable action, IActionSource source) {
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
        void collectAvailableStacks(KeyCounter out) {
            for (Map.Entry<ItemStack, BigInteger> entry : controller.itemStorage().entries()) {
                ItemStack stack = entry.getKey();
                if (stack.isEmpty()) {
                    continue;
                }
                AEItemKey key = AEItemKey.of(stack);
                if (key != null) {
                    out.add(key, clampLong(entry.getValue()));
                }
            }
        }

        @Override
        @Nullable
        AEKey aeKeyOf(ItemStack type) {
            if (type == null || type.isEmpty()) {
                return null;
            }
            return AEItemKey.of(type);
        }

        @Override
        BigInteger currentAmount(ItemStack type) {
            return controller.itemStorage().currentAmount(type);
        }

        @Override
        public ITextComponent getDescription() {
            return new TextComponentTranslation("applygray.machine.quantum_storage.chest.name");
        }
    }

    private static final class FluidStorageView extends ControllerStorageView<FluidStack> {

        private final MetaTileEntityQuantumFluidStorage controller;

        private FluidStorageView(MetaTileEntityQuantumFluidStorage controller) {
            this.controller = controller;
        }

        @Override
        public long insert(AEKey key, long amount, Actionable action, IActionSource source) {
            if (!AEFluidKey.is(key)) {
                return 0;
            }
            FluidStack fluid = ((AEFluidKey) key).toStack(1);
            if (fluid == null) {
                return 0;
            }
            BigInteger accepted = controller.insertFluid(fluid, BigInteger.valueOf(amount),
                    action.isSimulate());
            return clampLong(accepted);
        }

        @Override
        public long extract(AEKey key, long amount, Actionable action, IActionSource source) {
            if (!AEFluidKey.is(key)) {
                return 0;
            }
            FluidStack fluid = ((AEFluidKey) key).toStack(1);
            if (fluid == null) {
                return 0;
            }
            BigInteger removed = controller.extractFluid(fluid, BigInteger.valueOf(amount),
                    action.isSimulate());
            return clampLong(removed);
        }

        @Override
        void collectAvailableStacks(KeyCounter out) {
            for (Map.Entry<FluidStack, BigInteger> entry : controller.fluidStorage().entries()) {
                FluidStack fluid = entry.getKey();
                if (fluid == null || fluid.getFluid() == null) {
                    continue;
                }
                AEFluidKey key = AEFluidKey.of(fluid);
                if (key != null) {
                    out.add(key, clampLong(entry.getValue()));
                }
            }
        }

        @Override
        @Nullable
        AEKey aeKeyOf(FluidStack type) {
            if (type == null || type.getFluid() == null) {
                return null;
            }
            return AEFluidKey.of(type);
        }

        @Override
        BigInteger currentAmount(FluidStack type) {
            return controller.fluidStorage().currentAmount(type);
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
}
