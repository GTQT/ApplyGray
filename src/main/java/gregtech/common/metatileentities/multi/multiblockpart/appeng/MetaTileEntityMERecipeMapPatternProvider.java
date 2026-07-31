package gregtech.common.metatileentities.multi.multiblockpart.appeng;

import applygray.ApplyGrayMod;
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
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentTranslation;

import net.minecraftforge.common.util.Constants;

import ae2.api.crafting.IPatternDetails;
import ae2.api.implementations.blockentities.PatternContainerGroup;
import ae2.api.networking.IGrid;
import ae2.api.networking.IGridNodeListener;
import ae2.api.stacks.AEItemKey;
import ae2.api.stacks.GenericStack;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A direct, buffered ME pattern provider that lazily exposes recipes supported by
 * its own multiblock controller's RecipeMap.
 */
public class MetaTileEntityMERecipeMapPatternProvider extends MetaTileEntityMEPatternProvider {

    // Version 10 persists a provider planning mode and selected rule pin group alongside exact bindings.
    private static final int DYNAMIC_PATTERN_CACHE_VERSION = 10;
    private static final long PATTERN_CACHE_REFRESH_INTERVAL_TICKS = 20L;
    public static final String TERMINAL_GROUP_TOOLTIP_KEY = "applygray.gui.pattern_access.recipe_map_provider";

    private final AtomicLong dynamicEpoch = new AtomicLong();
    private final ConcurrentMap<String, DynamicRecipePatternDetails> cachedPatterns = new ConcurrentHashMap<>();
    private final AtomicBoolean patternCacheRefreshPending = new AtomicBoolean(true);
    private final AtomicBoolean patternCachePersistencePending = new AtomicBoolean();
    private volatile PlanningMode planningMode = PlanningMode.STOCK_FIRST;
    private volatile String pinnedRouteGroup = "";
    private long nextPatternCacheRefreshTick;
    private String lastRecipeMapSignature;
    private String lastMachineProfileVersion;
    private String lastRuleSetVersion;
    private MultiblockControllerBase lastController;

    public MetaTileEntityMERecipeMapPatternProvider(ResourceLocation metaTileEntityId, int tier) {
        super(metaTileEntityId, tier);
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
        // Dynamic patterns are registered by MixinCraftingGridCache on demand.
    }

    @Override
    public List<? extends IPatternDetails> getAvailablePatterns() {
        if (!isActive() || cachedPatterns.isEmpty()) {
            return Collections.emptyList();
        }

        List<DynamicRecipePatternDetails> patterns = new ArrayList<>(cachedPatterns.values());
        patterns.sort((left, right) -> left.getRecipeKey().compareTo(right.getRecipeKey()));
        return Collections.unmodifiableList(patterns);
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
                dynamic.getRecipeBinding(), dynamic.getTokenLayout());
    }

    @Override
    public int[] pushPatternMulti(IPatternDetails patternDetails,
                                  net.minecraft.inventory.InventoryCrafting table, int maxTodo) {
        if (maxTodo <= 0) return new int[]{0};
        KeyCounter[] inputs = new KeyCounter[table.getSizeInventory()];
        for (int index = 0; index < table.getSizeInventory(); index++) {
            inputs[index] = new KeyCounter();
            GenericStack stack = gregtech.api.util.AE2PatternCompat.toGenericStack(table.getStackInSlot(index));
            if (stack != null && stack.amount() > 0) {
                inputs[index].add(stack.what(), stack.amount());
            }
        }

        int pushed = 0;
        for (; pushed < maxTodo; pushed++) {
            if (!pushPattern(patternDetails, inputs, 1)) break;
        }
        if (pushed < maxTodo) {
            ApplyGrayMod.LOGGER.debug("Bound RecipeMap batch push accepted {} of {} tasks at {}", pushed, maxTodo,
                    getPos());
        }
        return new int[]{pushed};
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
    public void update() {
        super.update();
        if (getWorld() == null || getWorld().isRemote) return;

        if (getOffsetTimer() % 20 == 0) {
            RecipePatternRules.reloadIfChanged();
            MultiblockControllerBase controller = getController();
            RecipeMap<?>[] recipeMaps = getExposedRecipeMaps(controller);
            String recipeMapSignature = createRecipeMapSignature(recipeMaps);
            MachineCapabilityProfile machineProfile = MachineCapabilityProfile.capture(getDynamicProviderId(),
                    controller, recipeMaps, getBufferCount(), RecipePatternRules.collectMachineFacts(controller));
            String ruleSetVersion = RecipePatternRules.getActive().getVersion();
            boolean hadSource = lastController != null || lastRecipeMapSignature != null;
            if (controller != lastController || !recipeMapSignature.equals(lastRecipeMapSignature) ||
                    !machineProfile.getVersion().equals(lastMachineProfileVersion) ||
                    !ruleSetVersion.equals(lastRuleSetVersion)) {
                lastController = controller;
                lastRecipeMapSignature = recipeMapSignature;
                lastMachineProfileVersion = machineProfile.getVersion();
                lastRuleSetVersion = ruleSetVersion;
                dynamicEpoch.incrementAndGet();
                if (hadSource) clearCachedPatterns();
                patternCacheRefreshPending.set(true);
                ApplyGrayMod.LOGGER.info("RecipeMap pattern provider at {} changed source to {} (profile={}, rules={})",
                        getPos(), recipeMapSignature.isEmpty() ? "none" : recipeMapSignature,
                        machineProfile.getVersion().substring(0, 12), ruleSetVersion.substring(0, 12));
            }
            DynamicRecipePatternRegistry.refreshProvider(this);
        }
        flushCachedPatternUpdate();
    }

    @Override
    public void onMainNodeStateChanged(IGridNodeListener.State state) {
        if (getWorld() == null || getWorld().isRemote) {
            return;
        }

        if (isActive()) {
            DynamicRecipePatternRegistry.refreshProvider(this);
        } else {
            DynamicRecipePatternRegistry.unregister(this);
        }
        super.onMainNodeStateChanged(state);
        flushCachedPatternUpdate();
    }

    @Override
    public void destroyMainNode() {
        DynamicRecipePatternRegistry.unregister(this);
        super.destroyMainNode();
    }

    public DynamicRecipePatternDetails getCachedDynamicPattern(String recipeKey) {
        return cachedPatterns.get(recipeKey);
    }

    public List<DynamicRecipePatternDetails> getCachedDynamicPatterns() {
        return new ArrayList<>(cachedPatterns.values());
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

    public void removeCachedDynamicPattern(String recipeKey) {
        if (cachedPatterns.remove(recipeKey) != null) {
            queueCachedPatternUpdate();
        }
    }

    public void clearCachedPatterns() {
        if (!cachedPatterns.isEmpty()) {
            cachedPatterns.clear();
            queueCachedPatternUpdate();
        }
    }

    /** Clears this provider's persisted cache and invalidates its dynamic AE pattern registrations. */
    public int clearDynamicPatterns() {
        if (getWorld() == null || getWorld().isRemote) return 0;

        int cachedPatternCount = cachedPatterns.size();
        DynamicRecipePatternRegistry.clearProviderPatterns(this);
        clearCachedPatterns();
        markDirty();
        patternCachePersistencePending.set(false);
        patternCacheRefreshPending.set(requestPatternUpdate());
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
        int persistedPatternLimit = getPersistedPatternLimit();
        int count = 0;
        for (DynamicRecipePatternDetails detail : cachedPatterns.values()) {
            if (count++ >= persistedPatternLimit) {
                ApplyGrayMod.LOGGER.warn("RecipeMap pattern cache at {} exceeded the persisted limit of {} entries",
                        getPos(), persistedPatternLimit);
                break;
            }
            patterns.appendTag(detail.writeToNBT());
        }
        data.setTag("LazyRecipePatterns", patterns);
        data.setInteger("LazyRecipePatternVersion", DYNAMIC_PATTERN_CACHE_VERSION);
        return data;
    }

    @Override
    public void readFromNBT(NBTTagCompound data) {
        super.readFromNBT(data);
        planningMode = readPlanningMode(data.getString("RecipePatternPlanningMode"));
        pinnedRouteGroup = normalizePinnedRouteGroup(data.getString("RecipePatternPinnedRouteGroup"));
        cachedPatterns.clear();
        int persistedPatternLimit = getPersistedPatternLimit();
        int persistedVersion = data.getInteger("LazyRecipePatternVersion");
        if (persistedVersion == DYNAMIC_PATTERN_CACHE_VERSION &&
                data.hasKey("LazyRecipePatterns", Constants.NBT.TAG_LIST)) {
            NBTTagList patterns = data.getTagList("LazyRecipePatterns", Constants.NBT.TAG_COMPOUND);
            if (patterns.tagCount() > persistedPatternLimit) {
                ApplyGrayMod.LOGGER.warn("Truncated {} persisted RecipeMap patterns to {} at {}",
                        patterns.tagCount(), persistedPatternLimit, getPos());
            }
            for (int i = 0; i < Math.min(patterns.tagCount(), persistedPatternLimit); i++) {
                DynamicRecipePatternDetails detail = DynamicRecipePatternDetails.readFromNBT(
                        patterns.getCompoundTagAt(i));
                if (detail != null) {
                    cachedPatterns.putIfAbsent(detail.getRecipeKey(), detail);
                }
            }
        } else if (data.hasKey("LazyRecipePatterns", Constants.NBT.TAG_LIST)) {
            ApplyGrayMod.LOGGER.info("Discarded {} incompatible cached RecipeMap patterns at {}",
                    data.getTagList("LazyRecipePatterns", Constants.NBT.TAG_COMPOUND).tagCount(), getPos());
        }
        patternCacheRefreshPending.set(true);
        patternCachePersistencePending.set(false);
    }

    public String getDynamicProviderId() {
        if (getWorld() == null) return "unbound:" + System.identityHashCode(this);
        return getWorld().provider.getDimension() + ":" + getPos().toLong();
    }

    public DynamicRecipePatternRegistry.ProviderSnapshot createDynamicSnapshot() {
        if (getWorld() == null || getWorld().isRemote || !isActive()) return null;
        MultiblockControllerBase controller = getController();
        if (controller == null || !controller.isStructureFormed() || controller.getRecipeLogic() == null) return null;
        RecipeMap<?>[] recipeMaps = getExposedRecipeMaps(controller);
        if (recipeMaps.length == 0) return null;
        try {
            IGrid grid = getMainNode().getGrid();
            if (grid == null) return null;
            MachineCapabilityProfile machineProfile = MachineCapabilityProfile.capture(getDynamicProviderId(),
                    controller, recipeMaps, getBufferCount(), RecipePatternRules.collectMachineFacts(controller));
            return new DynamicRecipePatternRegistry.ProviderSnapshot(grid, getDynamicProviderId(),
                    dynamicEpoch.get(), recipeMaps, machineProfile, planningMode, pinnedRouteGroup, this);
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
        return "按 AE 请求懒生成样板\n配方表: " + recipeMaps[0].getLocalizedName() +
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

    private boolean isRecipeMapRouteAvailable(DynamicRecipePatternDetails detail) {
        for (RecipeMap<?> recipeMap : getExposedRecipeMaps(getController())) {
            if (recipeMap.getUnlocalizedName().equals(detail.getRecipeMapName()) &&
                    RecipeBindingResolver.resolve(detail.getRecipeBinding(), recipeMap).isResolved()) return true;
        }
        return false;
    }

    private boolean isBindingCurrent(DynamicRecipePatternDetails detail) {
        return detail.getRecipeBinding().getRuleSetVersion().equals(RecipePatternRules.getActive().getVersion()) &&
                isRecipeBindingCurrent(detail.getRecipeBinding());
    }

    /**
     * Re-samples only main-thread-safe controller facts before an already-buffered bound recipe is looked up.
     * Recipe identity itself is checked by {@code MixinMultiblockRecipeLogicRecipeBinding} immediately afterwards.
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
        boolean routeAvailable = false;
        for (RecipeMap<?> recipeMap : recipeMaps) {
            if (binding.isForRecipeMap(recipeMap.getUnlocalizedName())) {
                routeAvailable = true;
                break;
            }
        }
        if (!routeAvailable) return false;
        MachineCapabilityProfile profile = MachineCapabilityProfile.capture(getDynamicProviderId(), controller,
                recipeMaps, getBufferCount(), RecipePatternRules.collectMachineFacts(controller));
        return binding.getMachineProfileVersion().equals(profile.getVersion());
    }

    private void flushCachedPatternUpdate() {
        if (patternCachePersistencePending.getAndSet(false)) {
            markDirty();
        }
        if (!patternCacheRefreshPending.get() || !isActive() || getWorld() == null) {
            return;
        }

        long worldTime = getWorld().getTotalWorldTime();
        if (worldTime < nextPatternCacheRefreshTick) {
            return;
        }

        if (!patternCacheRefreshPending.compareAndSet(true, false)) {
            return;
        }
        nextPatternCacheRefreshTick = worldTime + PATTERN_CACHE_REFRESH_INTERVAL_TICKS;
        if (requestPatternUpdate()) {
            patternCacheRefreshPending.set(true);
        }
    }

    private void queueCachedPatternUpdate() {
        patternCachePersistencePending.set(true);
        patternCacheRefreshPending.set(true);
    }

    private void invalidatePlanningConfiguration(String change) {
        dynamicEpoch.incrementAndGet();
        if (getWorld() == null || getWorld().isRemote) return;

        int invalidated = DynamicRecipePatternRegistry.clearProviderPatterns(this);
        clearCachedPatterns();
        if (isActive()) {
            DynamicRecipePatternRegistry.refreshProviderImmediately(this);
        }
        markDirty();
        patternCacheRefreshPending.set(requestPatternUpdate());
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

    private static int getPersistedPatternLimit() {
        return RecipePatternRules.getActive().getPlanningBudget().getMaxPersistedPatternsPerProvider();
    }

    private static String createRecipeMapSignature(RecipeMap<?>[] recipeMaps) {
        StringBuilder signature = new StringBuilder();
        for (RecipeMap<?> recipeMap : recipeMaps) {
            if (signature.length() > 0) signature.append('|');
            signature.append(recipeMap.getUnlocalizedName());
        }
        return signature.toString();
    }
}
