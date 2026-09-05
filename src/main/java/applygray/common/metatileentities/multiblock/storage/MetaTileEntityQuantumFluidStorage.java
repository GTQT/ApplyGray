package applygray.common.metatileentities.multiblock.storage;

import applygray.common.ApplyGrayBlocks;
import applygray.common.blocks.BlockQuantumStorageUnit;
import applygray.common.blocks.QuantumStorageUnit;
import applygray.common.quantum.QuantumStorageHandler;
import applygray.common.quantum.QuantumStorageUnitScanner;

import gregtech.api.capability.GregtechTileCapabilities;
import gregtech.api.capability.IControllable;
import gregtech.api.capability.impl.FluidTankList;
import gregtech.api.metatileentity.IVoidable.VoidingMode;
import gregtech.api.metatileentity.MetaTileEntity;
import gregtech.api.metatileentity.interfaces.IGregTechTileEntity;
import gregtech.api.metatileentity.multiblock.IMultiblockPart;
import gregtech.api.metatileentity.multiblock.MultiblockAbility;
import gregtech.api.metatileentity.multiblock.MultiblockWithDisplayBase;
import gregtech.api.metatileentity.multiblock.ui.MultiblockUIBuilder;
import gregtech.api.pattern.FormedStructureView;
import gregtech.api.pattern.casing.DeclarativePatternBuilder;
import gregtech.api.pattern.element.StructureDefinition;
import gregtech.api.util.BlockInfo;
import gregtech.api.util.KeyUtil;
import gregtech.client.renderer.ICubeRenderer;
import gregtech.client.renderer.texture.Textures;
import gregtech.common.blocks.BlockMetalCasing;
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
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import org.jetbrains.annotations.NotNull;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import static gregtech.api.metatileentity.multiblock.MultiblockAbility.EXPORT_FLUIDS;
import static gregtech.api.metatileentity.multiblock.MultiblockAbility.IMPORT_FLUIDS;
import static gregtech.api.pattern.element.Elements.abilities;
import static gregtech.api.pattern.element.Elements.air;
import static gregtech.api.pattern.element.Elements.block;
import static gregtech.api.pattern.element.Elements.blockPredicate;
import static gregtech.api.pattern.element.Elements.choice;

/**
 * Large Quantum Fluid Storage Array: the fluid counterpart of
 * {@link MetaTileEntityQuantumItemStorage}. Contents live only on this
 * controller and are reachable through the fluid import/export hatches of the
 * structure (or an attached quantum access hatch).
 */
public class MetaTileEntityQuantumFluidStorage extends MultiblockWithDisplayBase implements IControllable {

    private static final int TICK_SECOND = 20;
    private static final String STORAGE_TAG = "QuantumStorage";

    private static final IBlockState CASING_STATE = MetaBlocks.METAL_CASING
            .getState(BlockMetalCasing.MetalCasingType.STEEL_SOLID);

    private static final StructureDefinition<?> STRUCTURE_DEFINITION = StructureDefinition.getOrBuild(
            "applygray:quantum_fluid_storage", () -> DeclarativePatternBuilder.start()
                    // front cap, controller in the middle of the face
                    .aisle("CCCCC", "CCCCC", "CCSCC", "CCCCC", "CCCCC")
                    // six unit core layers
                    .aisle("CCCCC", "CUUUC", "CUUUC", "CUUUC", "CCCCC")
                    .aisle("CCCCC", "CUUUC", "CUUUC", "CUUUC", "CCCCC")
                    .aisle("CCCCC", "CUUUC", "CUUUC", "CUUUC", "CCCCC")
                    .aisle("CCCCC", "CUUUC", "CUUUC", "CUUUC", "CCCCC")
                    .aisle("CCCCC", "CUUUC", "CUUUC", "CUUUC", "CCCCC")
                    .aisle("CCCCC", "CUUUC", "CUUUC", "CUUUC", "CCCCC")
                    // back cap
                    .aisle("CCCCC", "CCCCC", "CCCCC", "CCCCC", "CCCCC")
                    .self('S', MetaTileEntityQuantumFluidStorage.class)
                    .where('C', choice(
                            block(CASING_STATE),
                            abilities(0, 8, IMPORT_FLUIDS),
                            abilities(0, 8, EXPORT_FLUIDS),
                            abilities(1, 1, MultiblockAbility.MAINTENANCE_HATCH),
                            abilities(0, 1, MetaTileEntityQuantumAccessHatch.QUANTUM_ACCESS)))
                    .where('U', choice(
                            air(),
                            blockPredicate(state -> state.getBlock() instanceof BlockQuantumStorageUnit,
                                    MetaTileEntityQuantumFluidStorage::unitCandidates)))
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

    private static BlockInfo[] unitCandidates() {
        QuantumStorageUnit[] units = QuantumStorageUnit.values();
        BlockInfo[] infos = new BlockInfo[units.length];
        for (int i = 0; i < units.length; i++) {
            infos[i] = new BlockInfo(ApplyGrayBlocks.QUANTUM_STORAGE_UNIT.getState(units[i]));
        }
        return infos;
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
    protected void formStructure(FormedStructureView formed) {
        super.formStructure(formed);
        this.importFluids = new FluidTankList(true, getAbilities(IMPORT_FLUIDS));
        this.exportFluids = new FluidTankList(true, getAbilities(EXPORT_FLUIDS));
        QuantumStorageUnitScanner.Counts counts = QuantumStorageUnitScanner.scan(
                getWorld(), getPos(), getFrontFacing(), getUpwardsFacing(), isFlipped());
        this.storage.rebuild((int) Math.min(counts.distinctSlots, Integer.MAX_VALUE), counts.totalCapacity);
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
        return Textures.SOLID_STEEL_CASING;
    }

    @SideOnly(Side.CLIENT)
    @Override
    protected ICubeRenderer getFrontOverlay() {
        return Textures.PRIMITIVE_PUMP_OVERLAY;
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
