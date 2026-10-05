package applygray.common.metatileentities.multiblock.storage;

import applygray.common.quantum.QuantumStorageElement;
import applygray.common.quantum.QuantumStorageHandler;

import codechicken.lib.render.CCRenderState;
import codechicken.lib.render.pipeline.IVertexOperation;
import codechicken.lib.vec.Matrix4;

import gregtech.api.capability.GregtechTileCapabilities;
import gregtech.api.capability.IControllable;
import gregtech.api.capability.impl.FluidTankList;
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
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NonNull;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import static gregtech.api.metatileentity.multiblock.MultiblockAbility.EXPORT_FLUIDS;
import static gregtech.api.metatileentity.multiblock.MultiblockAbility.IMPORT_FLUIDS;
import static gregtech.api.pattern.element.Elements.abilities;
import static gregtech.api.pattern.element.Elements.block;
import static gregtech.api.pattern.element.Elements.choice;
import static gregtech.api.pattern.element.Elements.counted;
import static gregtech.api.util.RelativeDirection.FRONT;
import static gregtech.api.util.RelativeDirection.RIGHT;
import static gregtech.api.util.RelativeDirection.UP;

/**
 * Large Quantum Fluid Storage Array: the fluid counterpart of
 * {@link MetaTileEntityQuantumItemStorage}. GT Lite stacks this one vertically,
 * so the two end shells enclose a heat-vent cap, a fusion-glass core and a
 * second heat-vent cap. Contents live only on this controller and are reachable
 * through the fluid import/export hatches of the structure (or an attached
 * quantum access hatch).
 */
public class MetaTileEntityQuantumFluidStorage extends MultiblockWithDisplayBase implements IControllable {

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
            "applygray:quantum_fluid_storage", () -> DeclarativePatternBuilder.start(RIGHT, FRONT, UP)
                    // bottom shell ring
                    .piece("bottomShell")
                    .aisle("     ", " CCC ", " CCC ", " CCC ", "     ")
                    .end()
                    // heat-vent cap holding the controller
                    .piece("controllerCap")
                    .aisle("HCSCH", "HCCCH", "HCCCH", "HCCCH", "HCCCH")
                    .end()
                    // fusion-glass unit core, 1..15 layers
                    .repeatablePiece("core", MIN_CORE_LAYERS, MAX_CORE_LAYERS)
                    .aisle("GGGGG", "GUUUG", "GUUUG", "GUUUG", "GGGGG")
                    .withAisleChannel(GTStructureChannels.STRUCTURE_HEIGHT.getName())
                    .end()
                    // heat-vent cap
                    .piece("backCap")
                    .aisle("HCCCH", "HCCCH", "HCCCH", "HCCCH", "HCCCH")
                    .end()
                    // top shell ring
                    .piece("topShell")
                    .aisle("     ", " CCC ", " CCC ", " CCC ", "     ")
                    .end()
                    .self('S', MetaTileEntityQuantumFluidStorage.class)
                    .any(' ')
                    .block('H', getVentState())
                    .block('G', getGlassState())
                    .where('C', choice(
                            counted(MIN_CASING, Integer.MAX_VALUE, block(getCasingState())),
                            abilities(0, -1, 1, IMPORT_FLUIDS),
                            abilities(0, -1, 1, EXPORT_FLUIDS),
                            abilities(1, 1, MultiblockAbility.MAINTENANCE_HATCH),
                            abilities(0, 1, 1, MetaTileEntityQuantumAccessHatch.QUANTUM_ACCESS)))
                    .where('U', QuantumStorageElement.airOrStorageUnit())
                    .buildStructureDefinition());

    private final QuantumStorageHandler<FluidStack> storage = new QuantumStorageHandler<>(0, BigInteger.ZERO,
            FluidStack::isFluidEqual,
            (tag, fluid) -> tag.setTag("fluid", fluid.writeToNBT(new NBTTagCompound())),
            tag -> {
                FluidStack fluid = FluidStack.loadFluidStackFromNBT(tag.getCompoundTag("fluid"));
                fluid.amount = 1;
                return fluid;
            });

    private FluidTankList importFluids = new FluidTankList(true);
    private FluidTankList exportFluids = new FluidTankList(true);

    private boolean isWorkingEnabled = true;
    private boolean shouldImport;
    private boolean shouldExport;

    public MetaTileEntityQuantumFluidStorage(ResourceLocation metaTileEntityId) {
        super(metaTileEntityId);
    }

    @Override
    public MetaTileEntity createMetaTileEntity(IGregTechTileEntity tileEntity) {
        return new MetaTileEntityQuantumFluidStorage(metaTileEntityId);
    }

    @Override
    protected StructureDefinition<?> createStructureDefinition() {
        return STRUCTURE_DEFINITION;
    }

    @Override
    protected void formStructure(@NonNull FormedStructureView formed) {
        super.formStructure(formed);
        this.importFluids = new FluidTankList(true, getAbilities(IMPORT_FLUIDS));
        this.exportFluids = new FluidTankList(true, getAbilities(EXPORT_FLUIDS));
        // The core is a repeatable piece, so the totals come from the units matched by the pattern itself.
        QuantumStorageElement.Totals totals = QuantumStorageElement.read(formed);
        this.storage.rebuild((int) Math.min(totals.distinctSlots(), Integer.MAX_VALUE), totals.totalCapacity());
        this.shouldImport = importFluids.getTanks() > 0;
        this.shouldExport = exportFluids.getTanks() > 0;
    }

    @Override
    public void invalidateStructure() {
        this.importFluids = new FluidTankList(true);
        this.exportFluids = new FluidTankList(true);
        this.shouldImport = false;
        this.shouldExport = false;
        super.invalidateStructure();
    }

    @Override
    protected void updateFormedValid() {
        if (!getWorld().isRemote && isWorkingEnabled && getOffsetTimer() % TICK_SECOND == 0L) {
            if (shouldImport) {
                importFluids();
            }
            if (shouldExport) {
                exportFluids();
            }
        }
    }

    /** Big-integer entry point used by the quantum access hatch. */
    public BigInteger insertFluid(FluidStack fluid, BigInteger amount, boolean simulate) {
        FluidStack probe = fluid.copy();
        BigInteger insertable = storage.maxInsertable(probe);
        BigInteger accepted = amount.min(insertable);
        if (isVoidingFluids()) {
            accepted = amount;
        }
        if (!simulate && accepted.signum() > 0) {
            storage.insert(probe, accepted.min(insertable));
            markDirty();
        }
        return accepted;
    }

    /** Big-integer entry point used by the quantum access hatch. */
    public BigInteger extractFluid(FluidStack fluid, BigInteger amount, boolean simulate) {
        FluidStack probe = fluid.copy();
        BigInteger removed = amount.min(storage.currentAmount(probe));
        if (!simulate && removed.signum() > 0) {
            storage.extract(probe, removed);
            markDirty();
        }
        return removed;
    }

    public QuantumStorageHandler<FluidStack> fluidStorage() {
        return storage;
    }

    private boolean isVoidingFluids() {
        return getVoidingMode() == VoidingMode.VOID_FLUIDS.ordinal()
                || getVoidingMode() == VoidingMode.VOID_BOTH.ordinal();
    }

    private void importFluids() {
        for (int i = 0; i < importFluids.getTanks(); i++) {
            var tank = importFluids.getTankAt(i);
            FluidStack fluid = tank.getFluid();
            if (fluid == null || fluid.amount <= 0) {
                continue;
            }
            BigInteger accepted = storage.insert(fluid.copy(), BigInteger.valueOf(fluid.amount));
            if (accepted.signum() > 0) {
                tank.drain(accepted.intValueExact(), true);
                markDirty();
            } else if (isVoidingFluids()) {
                tank.drain(fluid.amount, true);
                markDirty();
            }
        }
    }

    private void exportFluids() {
        List<FluidStack> types = new ArrayList<>();
        for (var entry : storage.entries()) {
            types.add(entry.getKey());
        }
        for (FluidStack type : types) {
            BigInteger available = storage.currentAmount(type);
            if (available.signum() <= 0) {
                continue;
            }
            BigInteger fluidToMove = available.min(BigInteger.valueOf(Integer.MAX_VALUE));
            BigInteger moved = BigInteger.ZERO;
            for (int i = 0; i < exportFluids.getTanks() && fluidToMove.signum() > 0; i++) {
                var tank = exportFluids.getTankAt(i);
                FluidStack inside = tank.getFluid();
                if (inside != null && !inside.isFluidEqual(type)) {
                    continue;
                }
                int space = tank.getCapacity() - (inside == null ? 0 : inside.amount);
                if (space <= 0) {
                    continue;
                }
                int toFill = fluidToMove.min(BigInteger.valueOf(space)).intValue();
                int filled = tank.fill(new FluidStack(type, toFill), true);
                if (filled > 0) {
                    fluidToMove = fluidToMove.subtract(BigInteger.valueOf(filled));
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
                            "applygray.machine.quantum_storage.tank.total_capacity",
                            KeyUtil.number(TextFormatting.GREEN, totalCapacity)));

                    long stored = syncer.syncLong(() -> storage.totalStored().longValue());
                    keyManager.add(KeyUtil.lang(TextFormatting.GRAY,
                            "applygray.machine.quantum_storage.tank.stored",
                            KeyUtil.number(TextFormatting.YELLOW, stored)));

                    long distinct = syncer.syncInt(storage::distinctSlots);
                    long maxDistinct = syncer.syncInt(() -> (int) storage.maxDistinct());
                    keyManager.add(KeyUtil.lang(TextFormatting.GRAY,
                            "applygray.machine.quantum_storage.tank.slots",
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
        tooltip.add(I18n.format("applygray.machine.quantum_storage.tank.tooltip.1"));
        tooltip.add(I18n.format("applygray.machine.quantum_storage.tank.tooltip.2"));
        tooltip.add(I18n.format("applygray.machine.quantum_storage.tank.tooltip.3"));
        tooltip.add(I18n.format("applygray.machine.quantum_storage.tank.tooltip.4"));
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
