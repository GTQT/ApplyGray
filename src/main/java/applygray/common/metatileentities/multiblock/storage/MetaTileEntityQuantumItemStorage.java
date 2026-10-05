package applygray.common.metatileentities.multiblock.storage;

import applygray.common.quantum.QuantumStorageElement;
import applygray.common.quantum.QuantumStorageHandler;

import codechicken.lib.render.CCRenderState;
import codechicken.lib.render.pipeline.IVertexOperation;
import codechicken.lib.vec.Matrix4;

import gregtech.api.capability.GregtechTileCapabilities;
import gregtech.api.capability.IControllable;
import gregtech.api.capability.impl.ItemHandlerList;
import gregtech.api.metatileentity.MetaTileEntity;
import gregtech.api.metatileentity.interfaces.IGregTechTileEntity;
import gregtech.api.metatileentity.multiblock.IMultiblockPart;
import gregtech.api.metatileentity.multiblock.MultiblockAbility;
import gregtech.api.metatileentity.multiblock.MultiblockWithDisplayBase;
import gregtech.api.metatileentity.multiblock.ui.MultiblockUIBuilder;
import gregtech.api.pattern.FormedStructureView;
import gregtech.api.pattern.casing.DeclarativePatternBuilder;
import gregtech.api.pattern.casing.GTStructureChannels;
import gregtech.api.pattern.element.StructureDefinition;
import gregtech.api.util.KeyUtil;
import gregtech.client.renderer.ICubeRenderer;
import gregtech.client.renderer.texture.Textures;
import gregtech.common.blocks.BlockComputerCasing;
import gregtech.common.blocks.BlockGlassCasing;
import gregtech.common.blocks.MetaBlocks;

import net.minecraft.block.state.IBlockState;
import net.minecraft.client.resources.I18n;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NonNull;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import static gregtech.api.metatileentity.multiblock.MultiblockAbility.EXPORT_ITEMS;
import static gregtech.api.metatileentity.multiblock.MultiblockAbility.IMPORT_ITEMS;
import static gregtech.api.pattern.element.Elements.abilities;
import static gregtech.api.pattern.element.Elements.block;
import static gregtech.api.pattern.element.Elements.choice;
import static gregtech.api.pattern.element.Elements.counted;

/**
 * Large Quantum Item Storage Array: a passive multiblock that unifies the
 * capacity of up to 3x3x15 quantum storage units into one huge item store.
 * The heat-vent caps and fusion-glass core mirror GT Lite's original layout,
 * and the core repeats between {@value #MIN_CORE_LAYERS} and
 * {@value #MAX_CORE_LAYERS} layers. Contents live only on this controller and
 * are reachable through the item import/export hatches of the structure (or an
 * attached quantum access hatch).
 */
public class MetaTileEntityQuantumItemStorage extends MultiblockWithDisplayBase implements IControllable {

    private static final int TICK_SECOND = 20;
    private static final String STORAGE_TAG = "QuantumStorage";

    public static IBlockState getCasingState() {
        return MetaBlocks.COMPUTER_CASING.getState(BlockComputerCasing.CasingType.COMPUTER_CASING);
    }

    public static IBlockState getVentState() {
        return MetaBlocks.COMPUTER_CASING.getState(BlockComputerCasing.CasingType.COMPUTER_HEAT_VENT);
    }

    public static IBlockState getGlassState() {
        return MetaBlocks.TRANSPARENT_CASING.getState(BlockGlassCasing.CasingType.FUSION_GLASS);
    }

    /** Computer-casing count kept from GT Lite's {@code setMinGlobalLimited(12)}. */
    private static final int MIN_CASING = 12;

    /** Core layer bounds kept from GT Lite's {@code setRepeatable(1, 15)}. */
    private static final int MIN_CORE_LAYERS = 1;
    private static final int MAX_CORE_LAYERS = 15;

    private static final StructureDefinition<?> STRUCTURE_DEFINITION = StructureDefinition.getOrBuild(
            "applygray:quantum_item_storage", () -> DeclarativePatternBuilder.start()
                    // front heat-vent cap
                    .piece("frontCap")
                    .aisle("HHHHH", "HCCCH", "HCCCH", "HCCCH", "HHHHH")
                    .end()
                    // fusion-glass unit core, 1..15 layers
                    .repeatablePiece("core", MIN_CORE_LAYERS, MAX_CORE_LAYERS)
                    .aisle("GGGGG", "GUUUG", "GUUUG", "GUUUG", "GGGGG")
                    .withAisleChannel(GTStructureChannels.STRUCTURE_LENGTH.getName())
                    .end()
                    // back heat-vent cap, controller centred on its face
                    .piece("controllerCap")
                    .aisle("HHHHH", "HCCCH", "HCSCH", "HCCCH", "HHHHH")
                    .end()
                    .self('S', MetaTileEntityQuantumItemStorage.class)
                    .block('H', getVentState())
                    .block('G', getGlassState())
                    .where('C', choice(
                            counted(MIN_CASING, Integer.MAX_VALUE, block(getCasingState())),
                            abilities(0, -1, 1, IMPORT_ITEMS),
                            abilities(0, -1, 1, EXPORT_ITEMS),
                            abilities(1, 1, MultiblockAbility.MAINTENANCE_HATCH),
                            abilities(0, 1, 1, MetaTileEntityQuantumAccessHatch.QUANTUM_ACCESS)))
                    .where('U', QuantumStorageElement.airOrStorageUnit())
                    .buildStructureDefinition());

    private final QuantumStorageHandler<ItemStack> storage = new QuantumStorageHandler<>(0, BigInteger.ZERO,
            (a, b) -> a.isItemEqual(b) && ItemStack.areItemStackTagsEqual(a, b),
            (tag, stack) -> tag.setTag("item", stack.writeToNBT(new NBTTagCompound())),
            tag -> {
                ItemStack stack = new ItemStack(tag.getCompoundTag("item"));
                stack.setCount(1);
                return stack;
            });

    private ItemHandlerList importItems = new ItemHandlerList();
    private ItemHandlerList exportItems = new ItemHandlerList();

    private boolean isWorkingEnabled = true;
    private boolean shouldImport;
    private boolean shouldExport;

    public MetaTileEntityQuantumItemStorage(ResourceLocation metaTileEntityId) {
        super(metaTileEntityId);
    }

    @Override
    public MetaTileEntity createMetaTileEntity(IGregTechTileEntity tileEntity) {
        return new MetaTileEntityQuantumItemStorage(metaTileEntityId);
    }

    @Override
    protected @NonNull StructureDefinition<?> createStructureDefinition() {
        return STRUCTURE_DEFINITION;
    }

    @Override
    protected void formStructure(@NonNull FormedStructureView formed) {
        super.formStructure(formed);
        this.importItems = new ItemHandlerList(getAbilities(IMPORT_ITEMS));
        this.exportItems = new ItemHandlerList(getAbilities(EXPORT_ITEMS));
        // The core is a repeatable piece, so the totals come from the units matched by the pattern itself.
        QuantumStorageElement.Totals totals = QuantumStorageElement.read(formed);
        this.storage.rebuild((int) Math.min(totals.distinctSlots(), Integer.MAX_VALUE), totals.totalCapacity());
        this.shouldImport = importItems.getSlots() > 0;
        this.shouldExport = exportItems.getSlots() > 0;
    }

    @Override
    public void invalidateStructure() {
        this.importItems = new ItemHandlerList();
        this.exportItems = new ItemHandlerList();
        this.shouldImport = false;
        this.shouldExport = false;
        super.invalidateStructure();
    }

    @Override
    protected void updateFormedValid() {
        if (!getWorld().isRemote && isWorkingEnabled && getOffsetTimer() % TICK_SECOND == 0L) {
            if (shouldImport) {
                importItems();
            }
            if (shouldExport) {
                exportItems();
            }
        }
    }

    /** Big-integer entry point used by the quantum access hatch. */
    public BigInteger insertItemStack(ItemStack stack, BigInteger amount, boolean simulate) {
        ItemStack probe = stack.copy();
        BigInteger insertable = storage.maxInsertable(probe);
        BigInteger accepted = amount.min(insertable);
        if (isVoidingItems()) {
            accepted = amount;
        }
        if (!simulate && accepted.signum() > 0) {
            storage.insert(probe, accepted.min(insertable));
            markDirty();
        }
        return accepted;
    }

    /** Big-integer entry point used by the quantum access hatch. */
    public BigInteger extractItemStack(ItemStack stack, BigInteger amount, boolean simulate) {
        ItemStack probe = stack.copy();
        BigInteger removed = amount.min(storage.currentAmount(probe));
        if (!simulate && removed.signum() > 0) {
            storage.extract(probe, removed);
            markDirty();
        }
        return removed;
    }

    public QuantumStorageHandler<ItemStack> itemStorage() {
        return storage;
    }

    private boolean isVoidingItems() {
        return getVoidingMode() == VoidingMode.VOID_ITEMS.ordinal()
                || getVoidingMode() == VoidingMode.VOID_BOTH.ordinal();
    }

    private void importItems() {
        for (int slot = 0; slot < importItems.getSlots(); slot++) {
            ItemStack stack = importItems.getStackInSlot(slot);
            if (stack.isEmpty()) {
                continue;
            }
            BigInteger accepted = storage.insert(stack.copy(), BigInteger.valueOf(stack.getCount()));
            if (accepted.signum() > 0) {
                importItems.extractItem(slot, accepted.intValueExact(), false);
                markDirty();
            } else if (isVoidingItems()) {
                importItems.extractItem(slot, stack.getCount(), false);
                markDirty();
            }
        }
    }

    private void exportItems() {
        List<ItemStack> types = new ArrayList<>();
        for (var entry : storage.entries()) {
            types.add(entry.getKey());
        }
        for (ItemStack type : types) {
            BigInteger available = storage.currentAmount(type);
            if (available.signum() <= 0) {
                continue;
            }
            BigInteger stackToMove = available.min(BigInteger.valueOf(Integer.MAX_VALUE));
            BigInteger moved = BigInteger.ZERO;
            for (int slot = 0; slot < exportItems.getSlots() && stackToMove.signum() > 0; slot++) {
                int toInsert = stackToMove.min(BigInteger.valueOf(64)).intValue();
                ItemStack insertStack = type.copy();
                insertStack.setCount(toInsert);
                ItemStack leftover = exportItems.insertItem(slot, insertStack, false);
                int filled = toInsert - leftover.getCount();
                if (filled > 0) {
                    stackToMove = stackToMove.subtract(BigInteger.valueOf(filled));
                    moved = moved.add(BigInteger.valueOf(filled));
                }
            }
            if (moved.signum() > 0) {
                storage.extract(type, moved);
                markDirty();
            }
        }
    }

    @Override
    public boolean isWorkingEnabled() {
        return isWorkingEnabled;
    }

    @Override
    public void setWorkingEnabled(boolean isWorkingAllowed) {
        this.isWorkingEnabled = isWorkingAllowed;
        if (!getWorld().isRemote) {
            markDirty();
        }
    }

    @Override
    public boolean isActive() {
        return isWorkingEnabled && isStructureFormed();
    }

    @Override
    public <T> T getCapability(Capability<T> capability, EnumFacing side) {
        if (capability == GregtechTileCapabilities.CAPABILITY_CONTROLLABLE) {
            return GregtechTileCapabilities.CAPABILITY_CONTROLLABLE.cast(this);
        }
        return super.getCapability(capability, side);
    }

    @Override
    public boolean shouldShowVoidingModeButton() {
        return true;
    }

    @Override
    protected void configureDisplayText(MultiblockUIBuilder builder) {
        builder.setWorkingStatus(isWorkingEnabled(), isActive())
                .addCustom((keyManager, syncer) -> {
                    if (!isStructureFormed()) {
                        return;
                    }
                    long totalCapacity = syncer.syncLong(() -> storage.totalCapacity().longValue());
                    keyManager.add(KeyUtil.lang(TextFormatting.GRAY,
                            "applygray.machine.quantum_storage.chest.total_capacity",
                            KeyUtil.number(TextFormatting.GREEN, totalCapacity)));

                    long stored = syncer.syncLong(() -> storage.totalStored().longValue());
                    keyManager.add(KeyUtil.lang(TextFormatting.GRAY,
                            "applygray.machine.quantum_storage.chest.stored",
                            KeyUtil.number(TextFormatting.YELLOW, stored)));

                    long distinct = syncer.syncInt(storage::distinctSlots);
                    long maxDistinct = syncer.syncInt(() -> (int) storage.maxDistinct());
                    keyManager.add(KeyUtil.lang(TextFormatting.GRAY,
                            "applygray.machine.quantum_storage.chest.slots",
                            KeyUtil.number(TextFormatting.BLUE, distinct),
                            KeyUtil.number(TextFormatting.BLUE, maxDistinct)));
                })
                .addWorkingStatusLine();
    }

    @Override
    public boolean usesMui2() {
        return true;
    }

    @Override
    public boolean hasMufflerMechanics() {
        return false;
    }

    @Override
    public ICubeRenderer getBaseTexture(IMultiblockPart iMultiblockPart) {
        return Textures.COMPUTER_CASING;
    }

    @SideOnly(Side.CLIENT)
    @Override
    protected @NonNull ICubeRenderer getFrontOverlay() {
        return Textures.RESEARCH_STATION_OVERLAY;
    }

    @SideOnly(Side.CLIENT)
    @Override
    public void renderMetaTileEntity(CCRenderState renderState, Matrix4 translation, IVertexOperation[] pipeline) {
        super.renderMetaTileEntity(renderState, translation, pipeline);
        getFrontOverlay().renderOrientedState(renderState, translation, pipeline, getFrontFacing(), true,
                isStructureFormed());
    }

    @Override
    public void addInformation(ItemStack stack, @NotNull World world, @NotNull List<String> tooltip,
                               boolean advanced) {
        super.addInformation(stack, world, tooltip, advanced);
        tooltip.add(I18n.format("applygray.machine.quantum_storage.chest.tooltip.1"));
        tooltip.add(I18n.format("applygray.machine.quantum_storage.chest.tooltip.2"));
        tooltip.add(I18n.format("applygray.machine.quantum_storage.chest.tooltip.3"));
        tooltip.add(I18n.format("applygray.machine.quantum_storage.chest.tooltip.4"));
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound data) {
        super.writeToNBT(data);
        data.setTag(STORAGE_TAG, storage.serialize());
        return data;
    }

    @Override
    public void readFromNBT(NBTTagCompound data) {
        super.readFromNBT(data);
        if (data.hasKey(STORAGE_TAG)) {
            storage.deserialize(data.getCompoundTag(STORAGE_TAG));
        }
    }
}
