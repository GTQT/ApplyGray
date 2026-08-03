package gregtech.common.metatileentities.multi.multiblockpart.appeng;

import applygray.ApplyGrayMod;
import applygray.api.mui.ApplyGrayGuiTextures;
import applygray.integration.ae2.DynamicRecipePatternDetails;
import applygray.integration.ae2.DynamicRecipePatternRegistry;
import applygray.integration.ae2.recipe.MachineCapabilityProfile;
import applygray.integration.ae2.recipe.RecipeBinding;
import applygray.integration.ae2.recipe.RecipeBindingResolver;
import applygray.integration.ae2.rules.PlanningMode;
import applygray.integration.ae2.rules.RecipePatternRules;

import gregtech.api.metatileentity.MetaTileEntity;
import gregtech.api.capability.IMultipleRecipeMaps;
import gregtech.api.metatileentity.interfaces.IGregTechTileEntity;
import gregtech.api.metatileentity.multiblock.MultiblockControllerBase;
import gregtech.api.mui.GTGuiTextures;
import gregtech.api.recipes.RecipeMap;
import gregtech.api.util.GTLog;
import gregtech.common.items.MetaItems;
import gregtech.common.items.behaviors.ProgrammableCircuit;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentTranslation;

import net.minecraftforge.common.util.Constants;

import ae2.api.crafting.IPatternDetails;
import ae2.api.implementations.blockentities.PatternContainerGroup;
import ae2.api.networking.IGrid;
import ae2.api.networking.IGridNodeListener;
import ae2.api.stacks.AEItemKey;
import ae2.api.stacks.KeyCounter;
import com.cleanroommc.modularui.api.IPanelHandler;
import com.cleanroommc.modularui.api.drawable.IKey;
import com.cleanroommc.modularui.factory.PosGuiData;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.UISettings;
import com.cleanroommc.modularui.value.sync.IntSyncValue;
import com.cleanroommc.modularui.value.sync.PanelSyncManager;
import com.cleanroommc.modularui.value.sync.StringSyncValue;
import com.cleanroommc.modularui.widgets.ButtonWidget;
import com.cleanroommc.modularui.widgets.TextWidget;
import com.cleanroommc.modularui.widgets.layout.Flow;
import com.cleanroommc.modularui.widgets.textfield.TextFieldWidget;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A direct, buffered ME pattern provider that lazily exposes recipes supported by
 * its own multiblock controller's RecipeMap.
 */
public class MetaTileEntityMERecipeMapPatternProvider extends MetaTileEntityMEPatternProvider {

    /** Distinguishes restoring saved native details from an explicit dynamic planning request. */
    public enum DynamicSnapshotPurpose {
        PERSISTED_PATTERN_PUBLICATION,
        PATTERN_GENERATION
    }

    /**
     * Native AE2 batch-push cap. This reduces execution-side dispatch work without changing the pattern's
     * planning semantics or reserving more than the submitted job already owns.
     */
    private static final int MAX_MERGED_PATTERN_PUSH_MULTIPLIER = 64;
    private static final String FROZEN_STANDALONE_PATTERN_KEYS_TAG = "FrozenStandaloneRecipeMapPatterns";
    private static final long SLOW_SNAPSHOT_NANOS = TimeUnit.MILLISECONDS.toNanos(10);
    private static final long SLOW_SNAPSHOT_LOG_COOLDOWN_NANOS = TimeUnit.SECONDS.toNanos(5);
    /** Retry an unacknowledged native remount without turning a bad saved binding into per-tick AE2 work. */
    private static final long PERSISTED_PATTERN_PUBLICATION_INITIAL_RETRY_TICKS = 20L;
    private static final long PERSISTED_PATTERN_PUBLICATION_MAX_RETRY_TICKS = 100L;
    private static final int PERSISTED_PATTERN_PUBLICATION_MAX_RETRIES = 1;
    public static final String TERMINAL_GROUP_TOOLTIP_KEY = "applygray.gui.pattern_access.recipe_map_provider";

    private final AtomicLong dynamicEpoch = new AtomicLong();
    private final ConcurrentMap<String, DynamicRecipePatternDetails> cachedPatterns = new ConcurrentHashMap<>();
    /** Recipe keys selected by the most recent standalone generation graph. */
    private final Set<String> frozenStandalonePatternKeys = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean patternCachePersistencePending = new AtomicBoolean();
    /** An obsolete saved binding was discarded and must not be written back into the next chunk save. */
    private final AtomicBoolean discardedPersistedPatternCache = new AtomicBoolean();
    private final AtomicBoolean nativePatternSyncPending = new AtomicBoolean();
    private final AtomicLong lastSlowSnapshotLogNanos = new AtomicLong();
    private final AtomicLong lastSlowNativeRefreshLogNanos = new AtomicLong();
    private final AtomicLong lastPersistedPatternPublicationLogNanos = new AtomicLong();
    /** True when AE2's most recent native mount already received this provider's persisted pattern list. */
    private volatile boolean nativePatternListMounted;
    private volatile IGrid nativePatternListMountedGrid;
    private volatile int nativePatternListMountedCount;
    /** A persisted cache can be read after AE2 has already mounted this node with an empty native pattern list. */
    private final AtomicBoolean persistedPatternPublicationPending = new AtomicBoolean();
    private final AtomicBoolean persistedPatternPublicationTaskQueued = new AtomicBoolean();
    /** Grid expected to acknowledge the currently requested native remount. */
    private volatile IGrid persistedPatternPublicationRequestedGrid;
    private volatile long persistedPatternPublicationMountSequence;
    private volatile int persistedPatternPublicationLastMountPatternCount = -1;
    private volatile long persistedPatternPublicationNextRetryTick;
    private volatile int persistedPatternPublicationRetryCount;
    private volatile PlanningMode planningMode = PlanningMode.STOCK_FIRST;
    private volatile String pinnedRouteGroup = "";

    public MetaTileEntityMERecipeMapPatternProvider(ResourceLocation metaTileEntityId, int tier) {
        super(metaTileEntityId, tier);
        // AE2 already mounts this empty provider while it joins the grid. Only cache mutations need a later refresh.
        setNeedPatternSync(false);
    }

    @Override
    public MetaTileEntity createMetaTileEntity(IGregTechTileEntity tileEntity) {
        return new MetaTileEntityMERecipeMapPatternProvider(metaTileEntityId, getTier());
    }

    /** This provider intentionally owns no physical pattern slots. */
    @Override
    protected void initializeInventory() {
        super.initializeInventory();
        this.patternSlot = new net.minecraftforge.items.ItemStackHandler(0);
    }

    @Override
    public net.minecraftforge.items.ItemStackHandler getPatternSlot() {
        return null;
    }

    @Override
    public void setPatternDetails() {
        // This provider has no physical pattern slots. Generated details are published by getAvailablePatterns().
    }

    @Override
    public void onLoad() {
        super.onLoad();
        persistDiscardedPatternCache();
        schedulePersistedPatternPublication();
    }

    @Override
    public void update() {
        super.update();
        if (getWorld() != null && !getWorld().isRemote && persistedPatternPublicationPending.get() &&
                getOffsetTimer() % 20 == 0 && isPersistedPatternPublicationRetryDue()) {
            // A controller can finish forming after the node joins its grid. This retries only lightweight
            // registration and native publication; runtime furnace capture remains generation-only.
            schedulePersistedPatternPublication();
        }
    }

    @Override
    protected boolean shouldRefreshCraftingProviderAfterActivation() {
        // Grid.add mounts providers before the new grid has finished booting. If that mount already saw the saved
        // details, requesting another update for every provider after a cable merge only repeats the full AE2
        // unmount/remount work. A persisted recovery remains pending until AE2 has actually read a non-empty list
        // from the current grid, so it must keep the parent host's one activation refresh armed.
        return persistedPatternPublicationPending.get() ||
                hasCachedDynamicPatterns() && !nativePatternListMounted;
    }

    @Override
    protected boolean shouldRefreshCraftingProviderAfterDeactivation() {
        // isActive() is also false while a newly merged grid is booting. Only a real power/channel loss has to
        // unmount the provider; otherwise Grid.add has already retained its persisted details for the next state.
        return !getMainNode().isOnline();
    }

    @Override
    protected void refreshCraftingProviderPatterns() {
        long startedAt = System.nanoTime();
        super.refreshCraftingProviderPatterns();
        logSlowNativePatternRefresh("node activation", startedAt);
    }

    @Override
    public boolean requestPatternUpdate() {
        long startedAt = System.nanoTime();
        boolean retry = super.requestPatternUpdate();
        if (!retry) {
            logSlowNativePatternRefresh("pattern publication", startedAt);
        }
        return retry;
    }

    @Override
    public List<? extends IPatternDetails> getAvailablePatterns() {
        // isActive() additionally requires the grid to have finished booting. During Grid.add that condition is
        // briefly false even for a powered node that already owns a channel, which used to force one requestUpdate()
        // per persisted provider after every network merge. isOnline() keeps offline/channel-starved providers hidden
        // while allowing AE2's own initial mount to carry the persisted list across a topology change.
        IGrid currentGrid = getCurrentGridSafely();
        if (!getMainNode().isOnline() || cachedPatterns.isEmpty()) {
            recordNativePatternListMount(currentGrid, 0);
            return Collections.emptyList();
        }

        List<DynamicRecipePatternDetails> patterns = new ArrayList<>();
        for (DynamicRecipePatternDetails detail : cachedPatterns.values()) {
            if (DynamicRecipePatternRegistry.isPublishedDynamicPattern(detail, this)) {
                patterns.add(detail);
            }
        }
        if (patterns.isEmpty()) {
            recordNativePatternListMount(currentGrid, 0);
            return Collections.emptyList();
        }
        patterns.sort((left, right) -> left.getRecipeKey().compareTo(right.getRecipeKey()));
        recordNativePatternListMount(currentGrid, patterns.size());
        return Collections.unmodifiableList(patterns);
    }

    @Override
    public boolean canMergePatternPush(IPatternDetails patternDetails) {
        return getMaxPatternPushMultiplier(patternDetails, MAX_MERGED_PATTERN_PUSH_MULTIPLIER) > 1;
    }

    @Override
    public int getMaxPatternPushMultiplier(IPatternDetails patternDetails, int maxMultiplier) {
        DynamicRecipePatternDetails dynamic = DynamicRecipePatternRegistry.getDynamicPattern(patternDetails);
        if (maxMultiplier <= 0 || dynamic == null || !DynamicRecipePatternRegistry.owns(patternDetails, this)) {
            return 0;
        }

        long totalInputAmount = 0;
        for (IPatternDetails.IInput input : dynamic.getInputs()) {
            if (input == null || input.getMultiplier() <= 0 ||
                    input.getMultiplier() > Integer.MAX_VALUE - totalInputAmount) {
                return 0;
            }
            totalInputAmount += input.getMultiplier();
        }

        int safeMultiplier = Math.min(maxMultiplier, MAX_MERGED_PATTERN_PUSH_MULTIPLIER);
        if (totalInputAmount > 0) {
            safeMultiplier = Math.min(safeMultiplier, (int) (Integer.MAX_VALUE / totalInputAmount));
        }
        return Math.max(0, safeMultiplier);
    }

    @Override
    public boolean pushPattern(IPatternDetails patternDetails, KeyCounter[] inputHolder, int multiplier) {
        DynamicRecipePatternDetails dynamic = DynamicRecipePatternRegistry.getDynamicPattern(patternDetails);
        if (dynamic == null) {
            logPatternPushRejected("pattern is not a RecipeMap pattern");
            return false;
        }
        if (!DynamicRecipePatternRegistry.owns(patternDetails, this)) {
            logPatternPushRejected("pattern is not registered to this RecipeMap provider");
            return false;
        }
        if (multiplier <= 0 || multiplier > MAX_MERGED_PATTERN_PUSH_MULTIPLIER) {
            logPatternPushRejected("batch multiplier is outside the supported range");
            return false;
        }
        if (!isActive()) {
            logPatternPushRejected("machine is not active");
            return false;
        }
        if (!isRecipeMapRouteAvailable(dynamic)) {
            ApplyGrayMod.LOGGER.warn("Rejected stale RecipeMap pattern {} at {}", dynamic.getRecipeKey(), getPos());
            return false;
        }
        ItemStack circuit = createCircuitMetadata(dynamic);
        if (dynamic.getCircuitConfiguration() >= 0 && circuit.isEmpty()) {
            logPatternPushRejected("required circuit metadata could not be created");
            return false;
        }
        if (!isBindingCurrent(dynamic)) {
            ApplyGrayMod.LOGGER.warn("Rejected invalid RecipeMap binding {} at {}", dynamic.getRecipeBinding(),
                    getPos());
            return false;
        }
        return pushToBuffer(inputHolder, dynamic.getRecipeKey(), dynamic.getRecipeMapName(), circuit,
                dynamic.getRecipeBinding(), dynamic.getTokenLayout(), multiplier);
    }

    private ItemStack createCircuitMetadata(DynamicRecipePatternDetails detail) {
        if (detail.getCircuitConfiguration() < 0) return ItemStack.EMPTY;
        if (MetaItems.PROGRAMMABLE_CIRCUIT == null) return ItemStack.EMPTY;

        ItemStack circuit = gregtech.api.recipes.ingredients.IntCircuitIngredient
                .getIntegratedCircuit(detail.getCircuitConfiguration());
        if (circuit.isEmpty()) return ItemStack.EMPTY;

        ItemStack programmable = MetaItems.PROGRAMMABLE_CIRCUIT.getStackForm(1);
        ProgrammableCircuit.wrap(circuit, programmable);
        return programmable;
    }

    @Override
    public void gridChanged() {
        if (getWorld() == null || getWorld().isRemote) {
            return;
        }

        if (hasCachedDynamicPatterns()) {
            resetPersistedPatternPublicationRetryAfterLifecycleChange();
            // A node that was not registered before Grid.add was mounted with an empty native list. Rebuild only in
            // that case; normal grid moves retain their previous published details without a second AE2 remount.
            if (DynamicRecipePatternRegistry.refreshProviderAfterGridChange(this)) {
                setNeedPatternSync(true);
            }
            schedulePersistedPatternPublication();
        } else {
            // Empty providers are registered on demand by an explicit generation request.
            DynamicRecipePatternRegistry.unregister(this);
        }
    }

    @Override
    public void onMainNodeStateChanged(IGridNodeListener.State state) {
        if (getWorld() == null || getWorld().isRemote) {
            return;
        }

        if (hasCachedDynamicPatterns()) {
            resetPersistedPatternPublicationRetryAfterLifecycleChange();
            if (isActive()) {
                DynamicRecipePatternRegistry.refreshProviderAfterStateChange(this);
            }
            schedulePersistedPatternPublication();
            // A channel/topology transition can report inactive before the same node is moved to its new grid.
            // Keep the immutable snapshot through that gap so reconnecting a cable cannot make every provider capture
            // its controller again. Native publication is still removed by getAvailablePatterns() while offline.
        } else {
            DynamicRecipePatternRegistry.unregister(this);
        }
        super.onMainNodeStateChanged(state);
    }

    @Override
    public void destroyMainNode() {
        clearNativePatternListMount();
        resetPersistedPatternPublicationRetryAfterLifecycleChange();
        DynamicRecipePatternRegistry.unregister(this);
        super.destroyMainNode();
    }

    public DynamicRecipePatternDetails getCachedDynamicPattern(String recipeKey) {
        return cachedPatterns.get(recipeKey);
    }

    public List<DynamicRecipePatternDetails> getCachedDynamicPatterns() {
        return new ArrayList<>(cachedPatterns.values());
    }

    public boolean hasCachedDynamicPatterns() {
        return !cachedPatterns.isEmpty();
    }

    /** Returns whether this persisted detail belongs to any completed standalone graph. */
    public boolean isFrozenStandalonePattern(String recipeKey) {
        return recipeKey != null && frozenStandalonePatternKeys.contains(recipeKey);
    }

    /**
     * Marks newly selected details without demoting earlier, non-conflicting standalone graphs. Conflicting details
     * are removed through {@link #removeCachedDynamicPattern(String)} before this method is called.
     */
    public synchronized void addFrozenStandalonePatternKeys(Set<String> recipeKeys) {
        boolean changed = false;
        if (recipeKeys != null) {
            for (String recipeKey : recipeKeys) {
                if (recipeKey != null && cachedPatterns.containsKey(recipeKey)) {
                    changed |= frozenStandalonePatternKeys.add(recipeKey);
                }
            }
        }
        if (changed) patternCachePersistencePending.set(true);
    }

    /** Returns this provider's normal-request route objective; explicit optimal rebuilds still use RESOURCE_FIRST. */
    public PlanningMode getPlanningMode() {
        return planningMode;
    }

    /** Returns the route group required while {@link PlanningMode#PINNED} is active. */
    public String getPinnedRouteGroup() {
        return pinnedRouteGroup;
    }

    public void setPlanningMode(PlanningMode mode) {
        PlanningMode normalized = mode == null ? PlanningMode.STOCK_FIRST : mode;
        if (planningMode == normalized) return;
        planningMode = normalized;
        invalidatePlanningConfiguration("planningMode=" + normalized);
    }

    public void setPinnedRouteGroup(String group) {
        String normalized = normalizePinnedRouteGroup(group);
        if (pinnedRouteGroup.equals(normalized)) return;
        pinnedRouteGroup = normalized;
        invalidatePlanningConfiguration("pinnedRouteGroup=" +
                (normalized.isEmpty() ? "<unset>" : normalized));
    }

    public DynamicRecipePatternDetails cacheDynamicPattern(DynamicRecipePatternDetails detail) {
        DynamicRecipePatternDetails existing = cachedPatterns.putIfAbsent(detail.getRecipeKey(), detail);
        if (existing == null) {
            queueCachedPatternUpdate();
            return detail;
        }
        return existing;
    }

    /** Registers the exact detail instance selected by a completed task-local AE2 plan. */
    public synchronized void replaceCachedDynamicPattern(DynamicRecipePatternDetails detail) {
        DynamicRecipePatternDetails previous = cachedPatterns.put(detail.getRecipeKey(), detail);
        if (previous != detail) {
            queueCachedPatternUpdate();
        }
    }

    public void removeCachedDynamicPattern(String recipeKey) {
        if (cachedPatterns.remove(recipeKey) != null) {
            frozenStandalonePatternKeys.remove(recipeKey);
            if (cachedPatterns.isEmpty()) {
                clearPersistedPatternPublication();
            }
            queueCachedPatternUpdate();
        }
    }

    /** Rebuilds AE2's ordinary provider cache after the registry changes which saved details are published. */
    public void refreshDynamicPatternPublication() {
        queueNativePatternSync();
    }

    /**
     * Rebuilds AE2's provider snapshot on the server thread before a standalone generation is reported as ready.
     * This is also required when a provider contributed only stale patterns: AE2 must receive an empty snapshot to
     * unmount those details.
     */
    public boolean publishCachedPatternsImmediately() {
        if (getWorld() == null || getWorld().isRemote || !isActive()) {
            return false;
        }
        if (patternCachePersistencePending.getAndSet(false)) {
            markDirty();
        }
        boolean needsRetry = requestPatternUpdate();
        setNeedPatternSync(needsRetry);
        return !needsRetry;
    }

    public void clearCachedPatterns() {
        if (!cachedPatterns.isEmpty()) {
            cachedPatterns.clear();
            frozenStandalonePatternKeys.clear();
            clearPersistedPatternPublication();
            queueCachedPatternUpdate();
        } else if (!frozenStandalonePatternKeys.isEmpty()) {
            frozenStandalonePatternKeys.clear();
            queueCachedPatternUpdate();
        }
    }

    /** Clears this provider's persisted cache and invalidates its dynamic AE pattern registrations. */
    public int clearDynamicPatterns() {
        if (getWorld() == null || getWorld().isRemote) return 0;

        int cachedPatternCount = cachedPatterns.size();
        int invalidatedPatternCount = DynamicRecipePatternRegistry.clearProviderPatterns(this);
        clearCachedPatterns();
        DynamicRecipePatternRegistry.unregister(this);
        markDirty();
        patternCachePersistencePending.set(false);
        if (cachedPatternCount > 0 || invalidatedPatternCount > 0) {
            setNeedPatternSync(true);
        }
        if (cachedPatternCount > 0) {
            ApplyGrayMod.LOGGER.info("Cleared {} dynamic RecipeMap patterns at {}", cachedPatternCount, getPos());
        }
        return cachedPatternCount;
    }

    @Override
    public PatternContainerGroup getTerminalGroup() {
        List<ITextComponent> tooltip = new ArrayList<>();
        tooltip.add(new TextComponentTranslation(TERMINAL_GROUP_TOOLTIP_KEY));
        if (getWorld() != null) {
            tooltip.add(new TextComponentTranslation("applygray.gui.pattern_access.location",
                    getPos().getX(), getPos().getY(), getPos().getZ(), getWorld().provider.getDimension()));
        }
        return new PatternContainerGroup(AEItemKey.of(getStackForm()), getTerminalRecipeMapName(), tooltip);
    }

    private ITextComponent getTerminalRecipeMapName() {
        RecipeMap<?>[] recipeMaps = getExposedRecipeMaps();
        if (recipeMaps.length == 0) {
            return new TextComponentTranslation(getMetaFullName());
        }

        ITextComponent name = new TextComponentTranslation(recipeMaps[0].getTranslationKey());
        for (int i = 1; i < recipeMaps.length; i++) {
            name.appendText(", ");
            name.appendSibling(new TextComponentTranslation(recipeMaps[i].getTranslationKey()));
        }
        return name;
    }

    @Override
    public void onRemoval() {
        DynamicRecipePatternRegistry.unregister(this);
        clearCachedPatterns();
        super.onRemoval();
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound data) {
        super.writeToNBT(data);
        data.setString("RecipePatternPlanningMode", planningMode.name());
        data.setString("RecipePatternPinnedRouteGroup", pinnedRouteGroup);
        NBTTagList patterns = new NBTTagList();
        for (DynamicRecipePatternDetails detail : cachedPatterns.values()) {
            patterns.appendTag(detail.writeToNBT());
        }
        data.setTag("LazyRecipePatterns", patterns);
        NBTTagList frozenPatternKeys = new NBTTagList();
        for (String recipeKey : frozenStandalonePatternKeys) {
            if (cachedPatterns.containsKey(recipeKey)) {
                frozenPatternKeys.appendTag(new NBTTagString(recipeKey));
            }
        }
        data.setTag(FROZEN_STANDALONE_PATTERN_KEYS_TAG, frozenPatternKeys);
        return data;
    }

    @Override
    public void readFromNBT(NBTTagCompound data) {
        super.readFromNBT(data);
        planningMode = readPlanningMode(data.getString("RecipePatternPlanningMode"));
        pinnedRouteGroup = normalizePinnedRouteGroup(data.getString("RecipePatternPinnedRouteGroup"));
        cachedPatterns.clear();
        frozenStandalonePatternKeys.clear();
        discardedPersistedPatternCache.set(false);
        if (data.hasKey("LazyRecipePatterns", Constants.NBT.TAG_LIST)) {
            NBTTagList patterns = data.getTagList("LazyRecipePatterns", Constants.NBT.TAG_COMPOUND);
            int unreadablePatternCount = 0;
            int obsoletePatternCount = 0;
            for (int i = 0; i < patterns.tagCount(); i++) {
                try {
                    DynamicRecipePatternDetails detail = DynamicRecipePatternDetails.readFromNBT(
                            patterns.getCompoundTagAt(i));
                    if (detail != null && hasCurrentPersistedBinding(detail)) {
                        cachedPatterns.putIfAbsent(detail.getRecipeKey(), detail);
                    } else if (detail != null) {
                        obsoletePatternCount++;
                    } else {
                        unreadablePatternCount++;
                    }
                } catch (RuntimeException ignored) {
                    unreadablePatternCount++;
                }
            }
            if (unreadablePatternCount > 0) {
                ApplyGrayMod.LOGGER.warn("Skipped {} unreadable cached RecipeMap pattern(s) at {}",
                        unreadablePatternCount, getPos());
            }
            if (obsoletePatternCount > 0) {
                discardedPersistedPatternCache.set(true);
                ApplyGrayMod.LOGGER.warn("Discarded {} obsolete cached RecipeMap pattern(s) at {} " +
                                "(requires fingerprintVersion={} and normalizationVersion={})",
                        obsoletePatternCount, getPos(), RecipeBinding.FINGERPRINT_VERSION,
                        RecipeBinding.NORMALIZATION_VERSION);
            }
            NBTTagList frozenPatternKeys = data.getTagList(FROZEN_STANDALONE_PATTERN_KEYS_TAG,
                    Constants.NBT.TAG_STRING);
            for (int i = 0; i < frozenPatternKeys.tagCount(); i++) {
                String recipeKey = frozenPatternKeys.getStringTagAt(i);
                if (cachedPatterns.containsKey(recipeKey)) {
                    frozenStandalonePatternKeys.add(recipeKey);
                }
            }
        }
        patternCachePersistencePending.set(false);
        clearNativePatternListMount();
        if (cachedPatterns.isEmpty()) {
            clearPersistedPatternPublication();
        } else {
            beginPersistedPatternPublication();
            // The host keeps this one activation refresh armed if the node has not obtained a channel yet.
            queueCraftingProviderRefresh();
        }
        persistDiscardedPatternCache();
        schedulePersistedPatternPublication();
    }

    public String getDynamicProviderId() {
        if (getWorld() == null) return "unbound:" + System.identityHashCode(this);
        return getWorld().provider.getDimension() + ":" + getPos().toLong();
    }

    /**
     * Captures a worker-safe Provider definition on the server thread.
     *
     * <p>Runtime furnace fallbacks are expensive to enumerate, so only an explicit pattern-generation request may
     * fully capture them. Persisted-pattern publication restores at most the exact furnace inputs encoded by its
     * saved details and never enumerates the Vanilla furnace table from an AE2 lifecycle callback.</p>
     */
    public DynamicRecipePatternRegistry.ProviderSnapshot createDynamicSnapshot(DynamicSnapshotPurpose purpose) {
        if (getWorld() == null || getWorld().isRemote || !isActive()) return null;
        MultiblockControllerBase controller = getController();
        if (controller == null || !controller.isStructureFormed() || controller.getRecipeLogic() == null) return null;
        RecipeMap<?>[] recipeMaps = getExposedRecipeMaps(controller);
        if (recipeMaps.length == 0) return null;

        long snapshotStartedAt = System.nanoTime();
        long runtimeRecipePreparationNanos = 0;
        if (purpose == DynamicSnapshotPurpose.PATTERN_GENERATION) {
            long runtimeCaptureStartedAt = System.nanoTime();
            captureRuntimeRecipeMapsForPatternGeneration(recipeMaps);
            runtimeRecipePreparationNanos = System.nanoTime() - runtimeCaptureStartedAt;
        } else if (purpose == DynamicSnapshotPurpose.PERSISTED_PATTERN_PUBLICATION) {
            long runtimeRecoveryStartedAt = System.nanoTime();
            recoverPersistedRuntimeRecipeMaps(recipeMaps);
            runtimeRecipePreparationNanos = System.nanoTime() - runtimeRecoveryStartedAt;
        }
        try {
            IGrid grid = getMainNode().getGrid();
            if (grid == null) return null;
            long profileStartedAt = System.nanoTime();
            MachineCapabilityProfile machineProfile = MachineCapabilityProfile.capture(getDynamicProviderId(),
                    controller, recipeMaps, getBufferCount(), RecipePatternRules.collectMachineFacts(controller));
            long profileNanos = System.nanoTime() - profileStartedAt;
            DynamicRecipePatternRegistry.ProviderSnapshot snapshot = new DynamicRecipePatternRegistry.ProviderSnapshot(
                    grid, getDynamicProviderId(),
                    dynamicEpoch.get(), recipeMaps, machineProfile, planningMode, pinnedRouteGroup, this);
            logSlowSnapshot(purpose, recipeMaps.length, runtimeRecipePreparationNanos, profileNanos,
                    System.nanoTime() - snapshotStartedAt);
            return snapshot;
        } catch (IllegalStateException ignored) {
            return null;
        }
    }

    @Override
    public ModularPanel buildUI(PosGuiData guiData, PanelSyncManager guiSyncManager, UISettings settings) {
        IntSyncValue clearPatternAction = new IntSyncValue(
                () -> 0,
                value -> {
                    if (value > 0) clearDynamicPatterns();
                });
        guiSyncManager.syncValue("clear_dynamic_patterns", clearPatternAction);
        IntSyncValue refundAction = new IntSyncValue(
                () -> 0,
                value -> {
                    if (value <= 0 || getWorld() == null || getWorld().isRemote) return;
                    refundAll();
                    markDirty();
                });
        guiSyncManager.syncValue("refund_buffered_materials", refundAction);
        IPanelHandler planningSettings = guiSyncManager.syncedPanel("recipe_pattern_planning_settings", true,
                this::buildPlanningSettingsPopup);

        return gregtech.api.mui.GTGuis.createPanel(this, 176, 166)
                .child(IKey.lang(getMetaFullName()).asWidget().pos(7, 7))
                .child(new ButtonWidget<>()
                        .size(18)
                        .right(7)
                        .top(5)
                        .background(GTGuiTextures.BUTTON_CLEAR_GRID)
                        .disableHoverBackground()
                        .onMousePressed(mouseButton -> {
                            clearPatternAction.setIntValue(clearPatternAction.getIntValue() + 1);
                            return true;
                        })
                        .tooltip(tooltip -> tooltip.addLine(IKey.str("清理当前总成的动态样板"))))
                .child(new ButtonWidget<>()
                        .size(18)
                        .right(27)
                        .top(5)
                        .overlay(GTGuiTextures.FILTER_SETTINGS_OVERLAY)
                        .onMousePressed(mouseButton -> {
                            planningSettings.togglePanel();
                            return true;
                        })
                        .tooltip(tooltip -> tooltip.addLine(IKey.str("配置动态样板路线规划"))))
                .child(new ButtonWidget<>()
                        .size(18)
                        .right(47)
                        .top(5)
                        .overlay(ApplyGrayGuiTextures.EXPORT_OVERLAY)
                        .onMousePressed(mouseButton -> {
                            refundAction.setIntValue(refundAction.getIntValue() + 1);
                            return true;
                        })
                        .tooltip(tooltip -> tooltip.addLine(IKey.lang(
                                "applygray.gui.recipe_map_pattern_provider.refund_buffered_materials"))))
                .child(Flow.column()
                        .pos(7, 28)
                        .width(162)
                        .child(new TextWidget<>(IKey.dynamic(this::getStatusText))))
                .child(com.cleanroommc.modularui.widgets.SlotGroupWidget.playerInventory(false).left(7).bottom(7));
    }

    private ModularPanel buildPlanningSettingsPopup(PanelSyncManager syncManager, IPanelHandler syncHandler) {
        IntSyncValue planningModeValue = new IntSyncValue(
                () -> getPlanningMode().ordinal(),
                value -> setPlanningMode(planningModeForOrdinal(value)));
        StringSyncValue pinnedGroupValue = new StringSyncValue(this::getPinnedRouteGroup, this::setPinnedRouteGroup);
        syncManager.syncValue("recipe_pattern_planning_mode", planningModeValue);
        syncManager.syncValue("recipe_pattern_pinned_route_group", pinnedGroupValue);

        return gregtech.api.mui.GTGuis.createPopupPanel("recipe_pattern_planning", 142, 124)
                .child(IKey.str("动态路线规划").asWidget().left(5).top(5))
                .child(new ButtonWidget<>()
                        .size(16)
                        .left(5)
                        .top(22)
                        .overlay(IKey.str("<"))
                        .onMousePressed(mouseButton -> {
                            planningModeValue.setIntValue(Math.floorMod(planningModeValue.getIntValue() - 1,
                                    PlanningMode.values().length));
                            return true;
                        }))
                .child(new TextWidget<>(IKey.dynamic(() -> getPlanningMode().name()))
                        .left(25)
                        .top(25)
                        .width(88))
                .child(new ButtonWidget<>()
                        .size(16)
                        .right(5)
                        .top(22)
                        .overlay(IKey.str(">"))
                        .onMousePressed(mouseButton -> {
                            planningModeValue.setIntValue((planningModeValue.getIntValue() + 1) %
                                    PlanningMode.values().length);
                            return true;
                        }))
                .child(new TextFieldWidget()
                        .left(5)
                        .top(43)
                        .size(132, 14)
                        .setValidator(MetaTileEntityMERecipeMapPatternProvider::normalizePinnedRouteGroup)
                        .value(pinnedGroupValue)
                        .background(GTGuiTextures.DISPLAY))
                .child(new TextWidget<>(IKey.dynamic(this::getDiagnosticText))
                        .left(5)
                        .top(64)
                        .width(132));
    }

    private String getStatusText() {
        MultiblockControllerBase controller = getController();
        if (controller == null || !controller.isStructureFormed()) return "等待所属多方块成型";
        RecipeMap<?>[] recipeMaps = getExposedRecipeMaps(controller);
        if (recipeMaps.length == 0) {
            return "所属多方块没有可用配方表";
        }
        return "通过目标样板生成任务创建\n配方表: " + recipeMaps[0].getLocalizedName() +
                (recipeMaps.length > 1 ? " 等 " + recipeMaps.length + " 张" : "") +
                "\n缓冲区: " + getBufferCount() + " 个\n规划: " + planningMode +
                (planningMode == PlanningMode.PINNED ? " / " +
                        (pinnedRouteGroup.isEmpty() ? "未设置固定组" : pinnedRouteGroup) : "");
    }

    private String getDiagnosticText() {
        return DynamicRecipePatternRegistry.getProviderDiagnosticsText(getDynamicProviderId(), 3) + '\n' +
                DynamicRecipePatternRegistry.getPlanningMetricsSummary();
    }

    public RecipeMap<?>[] getExposedRecipeMaps() {
        return getExposedRecipeMaps(getController());
    }

    private RecipeMap<?>[] getExposedRecipeMaps(MultiblockControllerBase controller) {
        if (controller == null || controller.getRecipeLogic() == null) return new RecipeMap<?>[0];
        if (controller instanceof IMultipleRecipeMaps &&
                ((IMultipleRecipeMaps) controller).supportsRecipeMapPatternRouting()) {
            RecipeMap<?>[] available = ((IMultipleRecipeMaps) controller).getAvailableRecipeMaps();
            List<RecipeMap<?>> valid = new ArrayList<>();
            for (RecipeMap<?> recipeMap : available) {
                if (recipeMap != null && !valid.contains(recipeMap)) valid.add(recipeMap);
            }
            return valid.toArray(new RecipeMap<?>[0]);
        }
        RecipeMap<?> active = controller.getRecipeLogic().getRecipeMap();
        return active == null ? new RecipeMap<?>[0] : new RecipeMap<?>[]{active};
    }

    /** Captures lookup-only recipe sources only for an explicitly requested pattern generation. */
    private static void captureRuntimeRecipeMapsForPatternGeneration(RecipeMap<?>[] recipeMaps) {
        for (RecipeMap<?> recipeMap : RecipeBindingResolver.captureMainThreadRuntimeRecipes(recipeMaps)) {
            DynamicRecipePatternRegistry.invalidatePreparedRecipeMapContents(recipeMap);
        }
    }

    /** Restores only the saved electric-furnace routes required to publish this provider's persisted patterns. */
    private void recoverPersistedRuntimeRecipeMaps(RecipeMap<?>[] recipeMaps) {
        List<RecipeBindingResolver.PersistedFurnaceFallback> recoveries = new ArrayList<>();
        for (DynamicRecipePatternDetails detail : getCachedDynamicPatterns()) {
            RecipeBinding binding = detail.getRecipeBinding();
            if (binding == null || !binding.isForRecipeMap(detail.getRecipeMapName()) ||
                    !isExposedFurnaceRecipeMap(recipeMaps, detail.getRecipeMapName()) ||
                    !detail.getTokenLayout().isEmpty()) {
                continue;
            }

            IPatternDetails.IInput[] inputs = detail.getInputs();
            if (inputs.length != 1) continue;
            var options = inputs[0].possibleInputs();
            if (options.length != 1 || !(options[0].what() instanceof AEItemKey itemKey)) continue;

            ItemStack input = itemKey.toStack(1);
            if (!input.isEmpty()) {
                recoveries.add(new RecipeBindingResolver.PersistedFurnaceFallback(binding, input));
            }
        }
        RecipeBindingResolver.recoverPersistedFurnaceFallbacks(recipeMaps, recoveries);
    }

    private static boolean isExposedFurnaceRecipeMap(RecipeMap<?>[] recipeMaps, String recipeMapName) {
        if (recipeMaps == null || recipeMapName == null) return false;
        for (RecipeMap<?> recipeMap : recipeMaps) {
            if (recipeMap instanceof gregtech.api.recipes.machines.RecipeMapFurnace &&
                    recipeMapName.equals(recipeMap.getUnlocalizedName())) {
                return true;
            }
        }
        return false;
    }

    /** Reports only unusually slow server-thread snapshot work and rate-limits it per provider. */
    private void logSlowSnapshot(DynamicSnapshotPurpose purpose, int recipeMapCount, long runtimeRecipePreparationNanos,
                                 long profileNanos, long totalNanos) {
        if (totalNanos < SLOW_SNAPSHOT_NANOS) return;

        long now = System.nanoTime();
        long previous = lastSlowSnapshotLogNanos.get();
        if (previous != 0 && now - previous < SLOW_SNAPSHOT_LOG_COOLDOWN_NANOS) return;
        if (!lastSlowSnapshotLogNanos.compareAndSet(previous, now)) return;

        ApplyGrayMod.LOGGER.warn("Slow RecipeMap provider snapshot provider={} at {} purpose={} maps={} cached={} " +
                        "total={}ms runtimeRecipePreparation={}ms machineProfile={}ms",
                getDynamicProviderId(), getPos(), purpose, recipeMapCount, cachedPatterns.size(),
                TimeUnit.NANOSECONDS.toMillis(totalNanos), TimeUnit.NANOSECONDS.toMillis(runtimeRecipePreparationNanos),
                TimeUnit.NANOSECONDS.toMillis(profileNanos));
    }

    private void logSlowNativePatternRefresh(String source, long startedAt) {
        long elapsedNanos = System.nanoTime() - startedAt;
        if (elapsedNanos < SLOW_SNAPSHOT_NANOS) return;

        long now = System.nanoTime();
        long previous = lastSlowNativeRefreshLogNanos.get();
        if (previous != 0 && now - previous < SLOW_SNAPSHOT_LOG_COOLDOWN_NANOS) return;
        if (!lastSlowNativeRefreshLogNanos.compareAndSet(previous, now)) return;

        ApplyGrayMod.LOGGER.warn("Slow RecipeMap native pattern refresh provider={} at {} source={} cached={} " +
                        "elapsed={}ms",
                getDynamicProviderId(), getPos(), source, cachedPatterns.size(),
                TimeUnit.NANOSECONDS.toMillis(elapsedNanos));
    }

    private boolean isRecipeMapRouteAvailable(DynamicRecipePatternDetails detail) {
        for (RecipeMap<?> recipeMap : getExposedRecipeMaps(getController())) {
            if (recipeMap.getUnlocalizedName().equals(detail.getRecipeMapName()) &&
                    RecipeBindingResolver.resolve(detail.getRecipeBinding(), recipeMap).isResolved()) return true;
        }
        return false;
    }

    private boolean isBindingCurrent(DynamicRecipePatternDetails detail) {
        return isRecipeBindingCurrent(detail.getRecipeBinding());
    }

    /**
     * The profile and rules hashes belong to route selection, not recipe identity. Requiring those volatile values to
     * survive a world restart strands an otherwise exact persisted pattern before GregTech can run its normal
     * controller capability check. The resolver below still verifies the binding algorithm, recipe fingerprint, map,
     * and target output immediately before execution.
     */
    public boolean isRecipeBindingCurrent(RecipeBinding binding) {
        if (binding == null ||
                binding.getRecipeFingerprintVersion() != RecipeBinding.FINGERPRINT_VERSION ||
                binding.getNormalizationVersion() != RecipeBinding.NORMALIZATION_VERSION) {
            return false;
        }
        MultiblockControllerBase controller = getController();
        RecipeMap<?>[] recipeMaps = getExposedRecipeMaps(controller);
        if (recipeMaps.length == 0) return false;
        for (RecipeMap<?> recipeMap : recipeMaps) {
            if (binding.isForRecipeMap(recipeMap.getUnlocalizedName()) &&
                    RecipeBindingResolver.resolve(binding, recipeMap).isResolved()) return true;
        }
        return false;
    }

    private void queueCachedPatternUpdate() {
        clearNativePatternListMount();
        patternCachePersistencePending.set(true);
        queueNativePatternSync();
    }

    private static boolean hasCurrentPersistedBinding(DynamicRecipePatternDetails detail) {
        RecipeBinding binding = detail.getRecipeBinding();
        return binding != null && binding.getRecipeFingerprintVersion() == RecipeBinding.FINGERPRINT_VERSION &&
                binding.getNormalizationVersion() == RecipeBinding.NORMALIZATION_VERSION;
    }

    /** Persists the filtered cache without requesting a native AE2 provider refresh. */
    private void persistDiscardedPatternCache() {
        if (getWorld() == null || getWorld().isRemote ||
                !discardedPersistedPatternCache.compareAndSet(true, false)) {
            return;
        }
        markDirty();
    }

    /** Uses the same deferred native-provider update that ordinary pattern-slot changes use. */
    private void queueNativePatternSync() {
        if (getWorld() == null || getWorld().isRemote || !nativePatternSyncPending.compareAndSet(false, true)) {
            return;
        }
        MinecraftServer server = getWorld().getMinecraftServer();
        if (server == null) {
            nativePatternSyncPending.set(false);
            return;
        }
        server.addScheduledTask(() -> {
            nativePatternSyncPending.set(false);
            if (getWorld() == null || getWorld().isRemote) return;
            if (patternCachePersistencePending.getAndSet(false)) {
                markDirty();
            }
            setNeedPatternSync(true);
        });
    }

    /** Queues a recovery step after NBT has restored a native provider that AE2 may have mounted while empty. */
    private void schedulePersistedPatternPublication() {
        if (!persistedPatternPublicationPending.get() || getWorld() == null || getWorld().isRemote ||
                !persistedPatternPublicationTaskQueued.compareAndSet(false, true)) {
            return;
        }
        MinecraftServer server = getWorld().getMinecraftServer();
        if (server == null) {
            persistedPatternPublicationTaskQueued.set(false);
            return;
        }
        server.addScheduledTask(() -> {
            persistedPatternPublicationTaskQueued.set(false);
            publishPersistedPatternsWhenReady();
        });
    }

    private void publishPersistedPatternsWhenReady() {
        if (!persistedPatternPublicationPending.get()) return;
        if (getWorld() == null || getWorld().isRemote || !isActive()) return;
        if (!hasCachedDynamicPatterns()) {
            clearPersistedPatternPublication();
            return;
        }

        DynamicRecipePatternRegistry.refreshProviderAfterGridChange(this);
        if (!DynamicRecipePatternRegistry.hasRegisteredProviderSnapshotOnCurrentGrid(this)) {
            deferPersistedPatternPublication("provider snapshot is not registered on the current AE2 grid", true);
            return;
        }

        IGrid currentGrid = getCurrentGridSafely();
        if (currentGrid == null) {
            deferPersistedPatternPublication("node has no current AE2 grid", true);
            return;
        }
        int cachedCount = cachedPatterns.size();
        if (hasMountedAllPersistedPatterns(currentGrid, cachedCount)) {
            completePersistedPatternPublication(currentGrid);
            return;
        }
        if (!isPersistedPatternPublicationRetryDue()) {
            return;
        }

        // AE2 may already have mounted a partial list while the grid was booting. Do not remount it over and over
        // for a binding that the strict resolver has definitively rejected; a grid/controller lifecycle event will
        // re-arm recovery if its RecipeMap becomes available later.
        if (isNativePatternListMountedOn(currentGrid)) {
            DynamicRecipePatternRegistry.PersistedPatternPublicationStatus status =
                    DynamicRecipePatternRegistry.inspectPersistedPatternPublication(this);
            if (!status.hasRegisteredAllCachedPatterns() && !status.shouldRetryWithoutLifecycleChange()) {
                pausePersistedPatternPublication("AE2 mounted " + nativePatternListMountedCount + '/' + cachedCount +
                        " cached pattern(s); " + status.summarize());
                return;
            }
        }

        // requestUpdate() is synchronous in AE2, but its return type only reports node activity. Success is the
        // subsequent getAvailablePatterns() mount on this exact grid, not merely issuing the request.
        long mountSequenceBefore = persistedPatternPublicationMountSequence;
        persistedPatternPublicationRequestedGrid = currentGrid;
        persistedPatternPublicationLastMountPatternCount = -1;
        clearNativePatternListMount();
        queueCraftingProviderRefresh();

        if (hasMountedAllPersistedPatterns(currentGrid, cachedCount)) {
            completePersistedPatternPublication(currentGrid);
            return;
        }

        int observedPatternCount = persistedPatternPublicationMountSequence > mountSequenceBefore ?
                persistedPatternPublicationLastMountPatternCount : -1;
        DynamicRecipePatternRegistry.PersistedPatternPublicationStatus status =
                DynamicRecipePatternRegistry.inspectPersistedPatternPublication(this);
        boolean retryWithoutLifecycleChange = status.hasRegisteredAllCachedPatterns() ||
                status.shouldRetryWithoutLifecycleChange();
        String reason;
        if (observedPatternCount >= 0) {
            reason = "AE2 mounted " + observedPatternCount + '/' + cachedCount + " cached pattern(s); " +
                    status.summarize();
        } else {
            reason = "AE2 did not acknowledge the native provider remount; " + status.summarize();
        }
        deferPersistedPatternPublication(reason, retryWithoutLifecycleChange);
    }

    /** Marks recovery complete only after AE2 read every current persisted detail from this grid. */
    private void completePersistedPatternPublication(IGrid grid) {
        int cachedCount = cachedPatterns.size();
        int mountedCount = nativePatternListMountedGrid == grid ? nativePatternListMountedCount : 0;
        if (mountedCount < cachedCount) return;
        if (!persistedPatternPublicationPending.getAndSet(false)) return;

        persistedPatternPublicationRequestedGrid = null;
        persistedPatternPublicationNextRetryTick = 0;
        persistedPatternPublicationRetryCount = 0;
        persistedPatternPublicationLastMountPatternCount = -1;
        ApplyGrayMod.LOGGER.debug("Published {} persisted RecipeMap pattern(s) at {} without runtime recipe capture",
                mountedCount, getPos());
    }

    /**
     * Keeps an unacknowledged native remount pending with a bounded retry cadence. Strictly rejected details pause
     * immediately and are rechecked only after a real node/controller lifecycle transition.
     */
    private void deferPersistedPatternPublication(String reason, boolean retryWithoutLifecycleChange) {
        if (!retryWithoutLifecycleChange ||
                persistedPatternPublicationRetryCount >= PERSISTED_PATTERN_PUBLICATION_MAX_RETRIES) {
            pausePersistedPatternPublication(reason);
            return;
        }

        int retryCount = Math.min(persistedPatternPublicationRetryCount + 1, 4);
        persistedPatternPublicationRetryCount = retryCount;
        long delay = PERSISTED_PATTERN_PUBLICATION_INITIAL_RETRY_TICKS << Math.min(retryCount - 1, 2);
        delay = Math.min(delay, PERSISTED_PATTERN_PUBLICATION_MAX_RETRY_TICKS);
        persistedPatternPublicationNextRetryTick = getWorld().getTotalWorldTime() + delay;
        logDeferredPersistedPatternPublication(reason + "; retry in " + delay + " tick(s)");
    }

    private void pausePersistedPatternPublication(String reason) {
        persistedPatternPublicationNextRetryTick = Long.MAX_VALUE;
        logDeferredPersistedPatternPublication(reason + "; waiting for an AE2/controller state change");
    }

    private boolean isPersistedPatternPublicationRetryDue() {
        if (persistedPatternPublicationNextRetryTick == 0) return true;
        return getWorld() != null && getWorld().getTotalWorldTime() >= persistedPatternPublicationNextRetryTick;
    }

    private void beginPersistedPatternPublication() {
        persistedPatternPublicationPending.set(true);
        resetPersistedPatternPublicationRetryAfterLifecycleChange();
    }

    private void clearPersistedPatternPublication() {
        persistedPatternPublicationPending.set(false);
        persistedPatternPublicationRequestedGrid = null;
        persistedPatternPublicationNextRetryTick = 0;
        persistedPatternPublicationRetryCount = 0;
        persistedPatternPublicationLastMountPatternCount = -1;
    }

    /** A channel, grid, or controller transition gives a previously empty native mount another legitimate chance. */
    private void resetPersistedPatternPublicationRetryAfterLifecycleChange() {
        if (!persistedPatternPublicationPending.get()) return;
        persistedPatternPublicationRequestedGrid = null;
        persistedPatternPublicationNextRetryTick = 0;
        persistedPatternPublicationRetryCount = 0;
        persistedPatternPublicationLastMountPatternCount = -1;
    }

    private IGrid getCurrentGridSafely() {
        try {
            return getMainNode().getGrid();
        } catch (IllegalStateException ignored) {
            return null;
        }
    }

    private void recordNativePatternListMount(IGrid grid, int patternCount) {
        nativePatternListMounted = patternCount > 0;
        nativePatternListMountedGrid = patternCount > 0 ? grid : null;
        nativePatternListMountedCount = Math.max(0, patternCount);
        if (persistedPatternPublicationPending.get() && grid != null &&
                grid == persistedPatternPublicationRequestedGrid) {
            persistedPatternPublicationLastMountPatternCount = Math.max(0, patternCount);
            persistedPatternPublicationMountSequence++;
        }
    }

    private void clearNativePatternListMount() {
        nativePatternListMounted = false;
        nativePatternListMountedGrid = null;
        nativePatternListMountedCount = 0;
    }

    private boolean isNativePatternListMountedOn(IGrid grid) {
        return grid != null && nativePatternListMounted && nativePatternListMountedGrid == grid;
    }

    private boolean hasMountedAllPersistedPatterns(IGrid grid, int cachedCount) {
        return isNativePatternListMountedOn(grid) && nativePatternListMountedCount >= cachedCount;
    }

    private void logDeferredPersistedPatternPublication(String reason) {
        long now = System.nanoTime();
        long previous = lastPersistedPatternPublicationLogNanos.get();
        if (previous != 0 && now - previous < SLOW_SNAPSHOT_LOG_COOLDOWN_NANOS) return;
        if (!lastPersistedPatternPublicationLogNanos.compareAndSet(previous, now)) return;

        ApplyGrayMod.LOGGER.warn("Deferred persisted RecipeMap pattern publication at {}: {} (cached={}); " +
                        "the provider remains recoverable without runtime recipe capture",
                getPos(), reason, cachedPatterns.size());
    }

    private void invalidatePlanningConfiguration(String change) {
        dynamicEpoch.incrementAndGet();
        if (getWorld() == null || getWorld().isRemote) return;

        boolean hadCachedPatterns = hasCachedDynamicPatterns();
        int invalidated = DynamicRecipePatternRegistry.clearProviderPatterns(this);
        clearCachedPatterns();
        // Future explicit generation refreshes a fresh planning snapshot. Keeping an empty Provider registered only
        // makes later grid changes and world loads do unnecessary work.
        DynamicRecipePatternRegistry.unregister(this);
        markDirty();
        if (hadCachedPatterns || invalidated > 0) {
            setNeedPatternSync(true);
        }
        ApplyGrayMod.LOGGER.info("RecipeMap pattern provider at {} changed {}; invalidated {} dynamic pattern(s)",
                getPos(), change, invalidated);
    }

    private static PlanningMode readPlanningMode(String value) {
        try {
            return PlanningMode.valueOf(value);
        } catch (RuntimeException ignored) {
            return PlanningMode.STOCK_FIRST;
        }
    }

    private static PlanningMode planningModeForOrdinal(int ordinal) {
        PlanningMode[] values = PlanningMode.values();
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : PlanningMode.STOCK_FIRST;
    }

    private static String normalizePinnedRouteGroup(String group) {
        if (group == null) return "";
        String normalized = group.trim();
        return normalized.length() <= 96 ? normalized : normalized.substring(0, 96);
    }

}
