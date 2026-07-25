package gregtech.common.metatileentities.multi.multiblockpart.appeng;

import applygray.ApplyGrayMod;
import applygray.integration.ae2.DynamicRecipePatternDetails;
import applygray.integration.ae2.DynamicRecipePatternRegistry;

import gregtech.api.metatileentity.MetaTileEntity;
import gregtech.api.capability.IMultipleRecipeMaps;
import gregtech.api.metatileentity.interfaces.IGregTechTileEntity;
import gregtech.api.metatileentity.multiblock.MultiblockControllerBase;
import gregtech.api.recipes.RecipeMap;
import gregtech.api.util.GTLog;
import gregtech.common.items.MetaItems;
import gregtech.common.items.behaviors.ProgrammableCircuit;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.ResourceLocation;

import net.minecraftforge.common.util.Constants;

import ae2.api.crafting.IPatternDetails;
import ae2.api.networking.IGrid;
import ae2.api.stacks.KeyCounter;
import com.cleanroommc.modularui.api.drawable.IKey;
import com.cleanroommc.modularui.factory.PosGuiData;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.UISettings;
import com.cleanroommc.modularui.value.sync.PanelSyncManager;
import com.cleanroommc.modularui.widgets.TextWidget;
import com.cleanroommc.modularui.widgets.layout.Flow;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A direct, buffered ME pattern provider that lazily exposes recipes supported by
 * its own multiblock controller's RecipeMap.
 */
public class MetaTileEntityMERecipeMapPatternProvider extends MetaTileEntityMEPatternProvider {

    private static final int MAX_PERSISTED_PATTERNS = 1024;

    private final AtomicLong dynamicEpoch = new AtomicLong();
    private final ConcurrentMap<String, DynamicRecipePatternDetails> cachedPatterns = new ConcurrentHashMap<>();
    private String lastRecipeMapSignature;
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
    public boolean pushPattern(IPatternDetails patternDetails, KeyCounter[] inputHolder, int multiplier) {
        DynamicRecipePatternDetails dynamic = DynamicRecipePatternRegistry.getDynamicPattern(patternDetails);
        if (dynamic == null || !DynamicRecipePatternRegistry.owns(patternDetails, this) || !isActive()) {
            return false;
        }
        if (!isRecipeMapRouteAvailable(dynamic)) {
            ApplyGrayMod.LOGGER.warn("Rejected stale RecipeMap pattern {} at {}", dynamic.getRecipeKey(), getPos());
            return false;
        }
        ItemStack circuit = createCircuitMetadata(dynamic);
        if (dynamic.getCircuitConfiguration() >= 0 && circuit.isEmpty()) return false;
        return pushToBuffer(inputHolder, dynamic.getRecipeKey(), dynamic.getRecipeMapName(), circuit);
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
        if (getWorld() == null || getWorld().isRemote || getOffsetTimer() % 20 != 0) return;

        MultiblockControllerBase controller = getController();
        RecipeMap<?>[] recipeMaps = getExposedRecipeMaps(controller);
        String recipeMapSignature = createRecipeMapSignature(recipeMaps);
        boolean hadSource = lastController != null || lastRecipeMapSignature != null;
        if (controller != lastController || !recipeMapSignature.equals(lastRecipeMapSignature)) {
            lastController = controller;
            lastRecipeMapSignature = recipeMapSignature;
            dynamicEpoch.incrementAndGet();
            if (hadSource) clearCachedPatterns();
            ApplyGrayMod.LOGGER.info("RecipeMap pattern provider at {} changed source to {}",
                    getPos(), recipeMapSignature.isEmpty() ? "none" : recipeMapSignature);
        }
        DynamicRecipePatternRegistry.refreshProvider(this);
    }

    public DynamicRecipePatternDetails getCachedDynamicPattern(String recipeKey) {
        return cachedPatterns.get(recipeKey);
    }

    public void cacheDynamicPattern(DynamicRecipePatternDetails detail) {
        cachedPatterns.putIfAbsent(detail.getRecipeKey(), detail);
    }

    public void removeCachedDynamicPattern(String recipeKey) {
        cachedPatterns.remove(recipeKey);
    }

    public void clearCachedPatterns() {
        cachedPatterns.clear();
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
        NBTTagList patterns = new NBTTagList();
        int count = 0;
        for (DynamicRecipePatternDetails detail : cachedPatterns.values()) {
            if (count++ >= MAX_PERSISTED_PATTERNS) {
                ApplyGrayMod.LOGGER.warn("RecipeMap pattern cache at {} exceeded the persisted limit of {} entries",
                        getPos(), MAX_PERSISTED_PATTERNS);
                break;
            }
            patterns.appendTag(detail.writeToNBT());
        }
        data.setTag("LazyRecipePatterns", patterns);
        return data;
    }

    @Override
    public void readFromNBT(NBTTagCompound data) {
        super.readFromNBT(data);
        cachedPatterns.clear();
        if (!data.hasKey("LazyRecipePatterns", Constants.NBT.TAG_LIST)) return;
        NBTTagList patterns = data.getTagList("LazyRecipePatterns", Constants.NBT.TAG_COMPOUND);
        for (int i = 0; i < Math.min(patterns.tagCount(), MAX_PERSISTED_PATTERNS); i++) {
            DynamicRecipePatternDetails detail = DynamicRecipePatternDetails.readFromNBT(patterns.getCompoundTagAt(i));
            if (detail != null) cacheDynamicPattern(detail);
        }
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
            return new DynamicRecipePatternRegistry.ProviderSnapshot(grid, getDynamicProviderId(),
                    dynamicEpoch.get(), recipeMaps, this);
        } catch (IllegalStateException ignored) {
            return null;
        }
    }

    @Override
    public ModularPanel buildUI(PosGuiData guiData, PanelSyncManager guiSyncManager, UISettings settings) {
        return gregtech.api.mui.GTGuis.createPanel(this, 176, 166)
                .child(IKey.lang(getMetaFullName()).asWidget().pos(7, 7))
                .child(Flow.column()
                        .pos(7, 28)
                        .width(162)
                        .child(new TextWidget<>(IKey.dynamic(this::getStatusText))))
                .child(com.cleanroommc.modularui.widgets.SlotGroupWidget.playerInventory(false).left(7).bottom(7));
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
                "\n缓冲区: " + getBufferCount() + " 个";
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
            if (recipeMap.getUnlocalizedName().equals(detail.getRecipeMapName())) return true;
        }
        return false;
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
