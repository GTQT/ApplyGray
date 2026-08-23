package applygray.mattermanipulator.uplink;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import applygray.ApplyGrayMod;
import applygray.client.renderer.texture.ApplyGrayTextures;
import applygray.mattermanipulator.building.BlockSpec;
import applygray.mattermanipulator.inventory.ResourceRequirements;
import applygray.mattermanipulator.item.ItemMatterManipulator;
import applygray.mattermanipulator.state.ManipulatorCapability;

import gregtech.api.GTValues;
import gregtech.api.capability.GregtechDataCodes;
import gregtech.api.capability.GregtechTileCapabilities;
import gregtech.api.capability.IControllable;
import gregtech.api.capability.IEnergyContainer;
import gregtech.api.capability.impl.EnergyContainerList;
import gregtech.api.capability.impl.FluidTankList;
import gregtech.api.metatileentity.MetaTileEntity;
import gregtech.api.metatileentity.interfaces.IGregTechTileEntity;
import gregtech.api.metatileentity.multiblock.IMultiblockPart;
import gregtech.api.metatileentity.multiblock.MultiblockAbility;
import gregtech.api.metatileentity.multiblock.MultiblockWithDisplayBase;
import gregtech.api.pattern.FormedStructureView;
import gregtech.api.pattern.casing.DeclarativePatternBuilder;
import gregtech.api.pattern.element.StructureDefinition;
import gregtech.api.recipes.Recipe;
import gregtech.api.recipes.RecipeMaps;
import gregtech.api.recipes.ingredients.GTRecipeInput;
import gregtech.api.unification.material.Materials;
import gregtech.client.renderer.ICubeRenderer;
import gregtech.client.renderer.texture.Textures;
import gregtech.common.blocks.BlockFusionCasing;
import gregtech.common.blocks.BlockLargeMultiblockCasing;
import gregtech.common.blocks.BlockWireCoil;
import gregtech.common.blocks.MetaBlocks;

import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.World;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.IFluidTank;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import ae2.api.config.Actionable;
import ae2.api.networking.security.IActionSource;
import ae2.api.stacks.AEItemKey;
import ae2.api.stacks.AEFluidKey;
import ae2.api.storage.MEStorage;
import codechicken.lib.render.CCRenderState;
import codechicken.lib.render.pipeline.IVertexOperation;
import codechicken.lib.raytracer.CuboidRayTraceResult;
import codechicken.lib.vec.Matrix4;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import static gregtech.api.util.RelativeDirection.BACK;
import static gregtech.api.util.RelativeDirection.RIGHT;
import static gregtech.api.util.RelativeDirection.UP;

/**
 * Target-native Quantum Uplink controller.
 *
 * <p>The original 9x9x9 layout is retained. Its unavailable 1.7.10-only casing variants map to the closest current
 * GregTech high-tier states: Naquadah reinforced casing, Trinium wire coils, and fusion MK3 casing.</p>
 */
public final class MetaTileEntityQuantumUplink extends MultiblockWithDisplayBase implements UplinkEndpoint,
                                               UplinkCraftingEndpoint, IControllable {

    public static final MultiblockAbility<MetaTileEntityQuantumUplinkHatch> UPLINK_CONNECTOR =
            MultiblockAbility.ability("matter_manipulator_uplink_connector", MetaTileEntityQuantumUplinkHatch.class);

    public static final long PLASMA_EU_PER_ITEM = 131_072L;
    private static final long RUNNING_EUT = GTValues.VA[GTValues.ZPM];
    private static final long POWER_P2P_RESERVE_TICKS = 20L * 5L;
    private static final String ADDRESS_KEY = "UplinkAddress";
    private static final String PLASMA_ENERGY_KEY = "PlasmaEnergy";
    private static final String WORKING_ENABLED_KEY = "WorkingEnabled";

    private static final StructureDefinition<?> STRUCTURE_DEFINITION = StructureDefinition.getOrBuild(
            "applygray:matter_manipulator_quantum_uplink", () -> DeclarativePatternBuilder.start(RIGHT, UP, BACK)
                    .aisle("         ", "         ", "         ", "         ", "  AASAA  ", "         ",
                            "         ", "         ", "         ")
                    .aisle("         ", "         ", "  A   A  ", " AA   AA ", " AA   AA ", " AA   AA ",
                            "  A   A  ", "         ", "         ")
                    .aisle("         ", "  A   A  ", " ACCCCCA ", " AD   DA ", "A D   D A", " AD   DA ",
                            " ACCCCCA ", "  A   A  ", "         ")
                    .aisle("         ", " AA   AA ", " AD   DA ", "A       A", "A       A", "A       A",
                            " AD   DA ", " AA   AA ", "         ")
                    .aisle("  A   A  ", " AA   AA ", "A D   D A", "A       A", "ABBE EBBA", "A       A",
                            "A D   D A", " AA   AA ", "  A   A  ")
                    .aisle("         ", " AA   AA ", " AD   DA ", "A       A", "A       A", "A       A",
                            " AD   DA ", " AA   AA ", "         ")
                    .aisle("         ", "  A   A  ", " ACCCCCA ", " AD   DA ", "A D   D A", " AD   DA ",
                            " ACCCCCA ", "  A   A  ", "         ")
                    .aisle("         ", "         ", "  A   A  ", " AA   AA ", " AA   AA ", " AA   AA ",
                            "  A   A  ", "         ", "         ")
                    .aisle("         ", "         ", "         ", "         ", "  A   A  ", "         ",
                            "         ", "         ", "         ")
                    .self('S', MetaTileEntityQuantumUplink.class)
                    .casing('A', getCasingState())
                    .energyInput(1, 16)
                    .fluidInput(1, 16)
                    .maintenance()
                    .hatch(UPLINK_CONNECTOR, 1, 1)
                    .done()
                    .frames('B', Materials.NaquadahAlloy)
                    .frames('C', Materials.Trinium)
                    .block('D', MetaBlocks.WIRE_COIL.getState(BlockWireCoil.CoilType.TRINIUM))
                    .block('E', MetaBlocks.FUSION_CASING.getState(BlockFusionCasing.CasingType.FUSION_CASING_MK3))
                    .air(' ')
                    .buildStructureDefinition());

    private long address = MatterManipulatorUplinkRegistry.newAddress();
    private long storedPlasmaEnergy;
    private boolean workingEnabled = true;
    private boolean active;
    private boolean craftingQueueActive;
    private EnergyContainerList energyContainer = new EnergyContainerList(Collections.emptyList());
    private FluidTankList inputFluids = new FluidTankList(false);

    public MetaTileEntityQuantumUplink(ResourceLocation metaTileEntityId) {
        super(metaTileEntityId);
    }

    public static IBlockState getCasingState() {
        return MetaBlocks.LARGE_MULTIBLOCK_CASING
                .getState(BlockLargeMultiblockCasing.CasingType.NAQUADAH_REINFORCED_CASING);
    }

    @Override
    public MetaTileEntity createMetaTileEntity(IGregTechTileEntity tileEntity) {
        return new MetaTileEntityQuantumUplink(metaTileEntityId);
    }

    @Override
    public @NotNull EnumFacing getPreviewFrontFacing() {
        return EnumFacing.SOUTH;
    }

    @Override
    protected @NotNull StructureDefinition<?> createStructureDefinition() {
        return STRUCTURE_DEFINITION;
    }

    @Override
    protected void formStructure(@NotNull FormedStructureView formed) {
        formStructureWithDisplay(formed);
        energyContainer = new EnergyContainerList(getAbilities(MultiblockAbility.INPUT_ENERGY));
        inputFluids = new FluidTankList(false, getAbilities(MultiblockAbility.IMPORT_FLUIDS));
    }

    @Override
    public void invalidateStructure() {
        MetaTileEntityQuantumUplinkHatch connector = connector();
        if (connector != null) connector.cancelAllCraftingRequests();
        MatterManipulatorUplinkRegistry.unregister(this);
        active = false;
        craftingQueueActive = false;
        energyContainer = new EnergyContainerList(Collections.emptyList());
        inputFluids = new FluidTankList(false);
        super.invalidateStructure();
    }

    @Override
    public void onRemoval() {
        MetaTileEntityQuantumUplinkHatch connector = connector();
        if (connector != null) connector.cancelAllCraftingRequests();
        MatterManipulatorUplinkRegistry.unregister(this);
        craftingQueueActive = false;
        super.onRemoval();
    }

    @Override
    protected void updateFormedValid() {
        boolean shouldRun = workingEnabled && consumeRunningEnergy();
        setActive(shouldRun);
        MetaTileEntityQuantumUplinkHatch connector = connector();
        setCraftingQueueActive(shouldRun && connector != null && connector.hasCraftingRequests());
        if (shouldRun) {
            registerActiveEndpoint();
        } else {
            MatterManipulatorUplinkRegistry.unregister(this);
        }
    }

    @Override
    public long address() {
        return address;
    }

    @Override
    public UplinkStatus status() {
        if (!active || !isStructureFormed()) return UplinkStatus.OFFLINE;
        MetaTileEntityQuantumUplinkHatch connector = connector();
        if (connector == null || !connector.isOnline() || connector.getNetworkStorage() == null) {
            return UplinkStatus.AE_OFFLINE;
        }
        return hasPlasmaEnergy(PLASMA_EU_PER_ITEM) ? UplinkStatus.OK : UplinkStatus.NO_PLASMA;
    }

    @Override
    public UplinkCraftingRequestResult requestCrafting(EntityPlayerMP requester, String requestName,
                                                       ResourceRequirements requirements) {
        if (requester == null || requirements == null) {
            return UplinkCraftingRequestResult.rejected(UplinkCraftingRequestResult.Status.INVALID_REQUIREMENTS);
        }
        UplinkStatus status = status();
        if (status != UplinkStatus.OK) return rejectedCraftingRequest(status);
        MetaTileEntityQuantumUplinkHatch connector = connector();
        return connector == null
                ? UplinkCraftingRequestResult.rejected(UplinkCraftingRequestResult.Status.AE_OFFLINE)
                : connector.requestCrafting(requester.getUniqueID(), requestName, requirements);
    }

    @Override
    public int cancelCraftingRequests(java.util.UUID requesterId) {
        MetaTileEntityQuantumUplinkHatch connector = connector();
        return connector == null || requesterId == null ? 0 : connector.cancelCraftingRequests(requesterId);
    }

    @Override
    public long extract(BlockSpec specification, long amount, boolean simulate) {
        return transfer(specification, amount, simulate, false);
    }

    @Override
    public long insert(BlockSpec specification, long amount, boolean simulate) {
        return transfer(specification, amount, simulate, true);
    }

    @Override
    public long extract(FluidStack specification, long amount, boolean simulate) {
        return transferFluid(specification, amount, simulate, false);
    }

    @Override
    public long insert(FluidStack specification, long amount, boolean simulate) {
        return transferFluid(specification, amount, simulate, true);
    }

    @Override
    public boolean isActive() {
        return active;
    }

    @Override
    public long drainPower(long amount, boolean simulate) {
        if (amount <= 0L || !active || !isStructureFormed()) return 0L;

        long reserve = saturatingMultiply(RUNNING_EUT, POWER_P2P_RESERVE_TICKS);
        long available = Math.max(0L, energyContainer.getEnergyStored() - reserve);
        long transferable = Math.min(amount, available);
        if (simulate || transferable == 0L) return transferable;

        long removed = energyContainer.removeEnergy(transferable);
        return Math.max(0L, Math.min(transferable, -removed));
    }

    @Override
    public long restorePower(long amount) {
        if (amount <= 0L || !active || !isStructureFormed()) return 0L;
        return Math.max(0L, energyContainer.addEnergy(amount));
    }

    @Override
    public boolean isWorkingEnabled() {
        return workingEnabled;
    }

    @Override
    public void setWorkingEnabled(boolean workingEnabled) {
        if (this.workingEnabled == workingEnabled) return;
        this.workingEnabled = workingEnabled;
        markDirty();
        World world = getWorld();
        if (world != null && !world.isRemote) {
            writeCustomData(GregtechDataCodes.WORKING_ENABLED, buffer -> buffer.writeBoolean(workingEnabled));
        }
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound data) {
        super.writeToNBT(data);
        data.setLong(ADDRESS_KEY, address);
        data.setLong(PLASMA_ENERGY_KEY, storedPlasmaEnergy);
        data.setBoolean(WORKING_ENABLED_KEY, workingEnabled);
        return data;
    }

    @Override
    public void readFromNBT(NBTTagCompound data) {
        super.readFromNBT(data);
        address = data.getLong(ADDRESS_KEY);
        if (address == 0L) address = MatterManipulatorUplinkRegistry.newAddress();
        storedPlasmaEnergy = Math.max(0L, data.getLong(PLASMA_ENERGY_KEY));
        workingEnabled = !data.hasKey(WORKING_ENABLED_KEY) || data.getBoolean(WORKING_ENABLED_KEY);
    }

    @Override
    public void writeItemStackData(NBTTagCompound itemStack) {
        itemStack.setLong(ADDRESS_KEY, address);
    }

    @Override
    public void initFromItemStackData(NBTTagCompound itemStack) {
        long itemAddress = itemStack.getLong(ADDRESS_KEY);
        address = itemAddress == 0L ? MatterManipulatorUplinkRegistry.newAddress() : itemAddress;
    }

    @Override
    public void writeInitialSyncData(PacketBuffer buffer) {
        super.writeInitialSyncData(buffer);
        buffer.writeBoolean(active);
        buffer.writeBoolean(workingEnabled);
        buffer.writeBoolean(craftingQueueActive);
    }

    @Override
    public void receiveInitialSyncData(PacketBuffer buffer) {
        super.receiveInitialSyncData(buffer);
        active = buffer.readBoolean();
        workingEnabled = buffer.readBoolean();
        craftingQueueActive = buffer.readBoolean();
    }

    @Override
    public void receiveCustomData(int dataId, @NotNull PacketBuffer buffer) {
        super.receiveCustomData(dataId, buffer);
        if (dataId == GregtechDataCodes.WORKABLE_ACTIVE) {
            active = buffer.readBoolean();
            scheduleRenderUpdate();
        } else if (dataId == GregtechDataCodes.WORKING_ENABLED) {
            workingEnabled = buffer.readBoolean();
            scheduleRenderUpdate();
        } else if (dataId == GregtechDataCodes.UPDATE_ACTIVE) {
            craftingQueueActive = buffer.readBoolean();
            scheduleRenderUpdate();
        }
    }

    @Override
    public boolean onRightClick(EntityPlayer player, EnumHand hand, EnumFacing facing,
                                CuboidRayTraceResult hitResult) {
        ItemStack held = player.getHeldItem(hand);
        if (!(held.getItem() instanceof ItemMatterManipulator manipulator) ||
                !manipulator.hasCapability(held, ManipulatorCapability.UPLINK)) {
            return super.onRightClick(player, hand, facing, hitResult);
        }
        if (!player.isSneaking()) return super.onRightClick(player, hand, facing, hitResult);

        World world = getWorld();
        if (world != null && !world.isRemote) {
            if (status() == UplinkStatus.OFFLINE) {
                player.sendStatusMessage(new TextComponentTranslation("applygray.matter_manipulator.uplink.offline"), true);
            } else if (manipulator.setUplinkAddress(held, address)) {
                player.sendStatusMessage(new TextComponentTranslation("applygray.matter_manipulator.uplink.bound",
                        Long.toUnsignedString(address, 16)), true);
            }
        }
        return true;
    }

    @Override
    public ICubeRenderer getBaseTexture(IMultiblockPart sourcePart) {
        return Textures.NAQUADAH_REINFORCED_CASING;
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void renderMetaTileEntity(CCRenderState renderState, Matrix4 translation, IVertexOperation[] pipeline) {
        super.renderMetaTileEntity(renderState, translation, pipeline);
        ApplyGrayTextures.MATTER_MANIPULATOR_UPLINK_FRONT_OFF.renderSided(getFrontFacing(), renderState, translation,
                pipeline);
        if (!active) return;
        (craftingQueueActive ? ApplyGrayTextures.MATTER_MANIPULATOR_UPLINK_FRONT_ACTIVE_GLOW
                : ApplyGrayTextures.MATTER_MANIPULATOR_UPLINK_FRONT_IDLE_GLOW).renderSided(getFrontFacing(),
                        renderState, translation, pipeline);
    }

    @Override
    public boolean hasMaintenanceMechanics() {
        return true;
    }

    @Override
    public <T> T getCapability(Capability<T> capability, @Nullable EnumFacing side) {
        if (capability == GregtechTileCapabilities.CAPABILITY_CONTROLLABLE) {
            return GregtechTileCapabilities.CAPABILITY_CONTROLLABLE.cast(this);
        }
        return super.getCapability(capability, side);
    }

    private long transfer(BlockSpec specification, long amount, boolean simulate, boolean insert) {
        if (amount <= 0L || specification == null || specification.isAir()) return 0L;
        MetaTileEntityQuantumUplinkHatch connector = connector();
        if (connector == null || status() != UplinkStatus.OK) return 0L;

        MEStorage storage = connector.getNetworkStorage();
        AEItemKey key = AEItemKey.of(specification.toStack());
        if (storage == null || key == null) return 0L;

        IActionSource actionSource = connector.getActionSource();
        long accepted = insert
                ? storage.insert(key, amount, Actionable.SIMULATE, actionSource)
                : storage.extract(key, amount, Actionable.SIMULATE, actionSource);
        long transferable = Math.min(accepted, availablePlasmaEnergy() / PLASMA_EU_PER_ITEM);
        if (transferable <= 0L) return 0L;
        if (simulate) return transferable;
        long transferred = insert
                ? storage.insert(key, transferable, Actionable.MODULATE, actionSource)
                : storage.extract(key, transferable, Actionable.MODULATE, actionSource);
        if (transferred <= 0L) return 0L;

        if (consumePlasmaEnergy(saturatingMultiply(transferred, PLASMA_EU_PER_ITEM))) return transferred;

        long restored = insert
                ? storage.extract(key, transferred, Actionable.MODULATE, actionSource)
                : storage.insert(key, transferred, Actionable.MODULATE, actionSource);
        if (restored != transferred) {
            ApplyGrayMod.LOGGER.warn("Matter Manipulator uplink {} could not fully compensate a failed plasma charge",
                    Long.toUnsignedString(address, 16));
        }
        return 0L;
    }

    private long transferFluid(FluidStack specification, long amount, boolean simulate, boolean insert) {
        if (amount <= 0L || specification == null || specification.amount <= 0) return 0L;
        MetaTileEntityQuantumUplinkHatch connector = connector();
        if (connector == null || status() != UplinkStatus.OK) return 0L;
        MEStorage storage = connector.getNetworkStorage();
        AEFluidKey key = AEFluidKey.of(specification);
        if (storage == null || key == null) return 0L;
        IActionSource actionSource = connector.getActionSource();
        long accepted = insert ? storage.insert(key, amount, Actionable.SIMULATE, actionSource)
                : storage.extract(key, amount, Actionable.SIMULATE, actionSource);
        long transferable = Math.min(accepted, availablePlasmaEnergy() / PLASMA_EU_PER_ITEM);
        if (transferable <= 0L) return 0L;
        if (simulate) return transferable;
        long transferred = insert ? storage.insert(key, transferable, Actionable.MODULATE, actionSource)
                : storage.extract(key, transferable, Actionable.MODULATE, actionSource);
        if (transferred <= 0L) return 0L;
        if (consumePlasmaEnergy(saturatingMultiply(transferred, PLASMA_EU_PER_ITEM))) return transferred;
        long restored = insert ? storage.extract(key, transferred, Actionable.MODULATE, actionSource)
                : storage.insert(key, transferred, Actionable.MODULATE, actionSource);
        if (restored != transferred) {
            ApplyGrayMod.LOGGER.warn("Matter Manipulator uplink {} could not fully compensate a failed fluid plasma charge",
                    Long.toUnsignedString(address, 16));
        }
        return 0L;
    }

    @Nullable
    private MetaTileEntityQuantumUplinkHatch connector() {
        for (MetaTileEntityQuantumUplinkHatch candidate : getAbilities(UPLINK_CONNECTOR)) {
            if (candidate != null) return candidate;
        }
        return null;
    }

    private static UplinkCraftingRequestResult rejectedCraftingRequest(UplinkStatus status) {
        return switch (status) {
            case OFFLINE -> UplinkCraftingRequestResult.rejected(UplinkCraftingRequestResult.Status.OFFLINE);
            case AE_OFFLINE -> UplinkCraftingRequestResult.rejected(UplinkCraftingRequestResult.Status.AE_OFFLINE);
            case NO_PLASMA -> UplinkCraftingRequestResult.rejected(UplinkCraftingRequestResult.Status.NO_PLASMA);
            case OK -> throw new IllegalArgumentException("Online uplinks must not reject an OK status");
        };
    }

    private boolean consumeRunningEnergy() {
        return energyContainer.getEnergyStored() >= RUNNING_EUT && energyContainer.removeEnergy(RUNNING_EUT) == -RUNNING_EUT;
    }

    private void setActive(boolean active) {
        if (this.active == active) return;
        this.active = active;
        markDirty();
        World world = getWorld();
        if (world != null && !world.isRemote) {
            writeCustomData(GregtechDataCodes.WORKABLE_ACTIVE, buffer -> buffer.writeBoolean(active));
        }
    }

    private void setCraftingQueueActive(boolean craftingQueueActive) {
        if (this.craftingQueueActive == craftingQueueActive) return;
        this.craftingQueueActive = craftingQueueActive;
        World world = getWorld();
        if (world != null && !world.isRemote) {
            writeCustomData(GregtechDataCodes.UPDATE_ACTIVE, buffer -> buffer.writeBoolean(craftingQueueActive));
        }
    }

    private void registerActiveEndpoint() {
        if (MatterManipulatorUplinkRegistry.register(this)) return;

        long replacedAddress = address;
        address = MatterManipulatorUplinkRegistry.newAddress();
        MatterManipulatorUplinkRegistry.register(this);
        markDirty();
        ApplyGrayMod.LOGGER.warn("Regenerated duplicate Matter Manipulator uplink address {} at {}", 
                Long.toUnsignedString(replacedAddress, 16), getPos());
    }

    private boolean hasPlasmaEnergy(long required) {
        return required >= 0L && availablePlasmaEnergy() >= required;
    }

    private boolean consumePlasmaEnergy(long required) {
        if (!hasPlasmaEnergy(required)) return false;
        while (storedPlasmaEnergy < required) {
            long remaining = required - storedPlasmaEnergy;
            if (!generatePlasmaEnergy(remaining)) return false;
        }
        storedPlasmaEnergy -= required;
        markDirty();
        return true;
    }

    private boolean generatePlasmaEnergy(long needed) {
        for (IFluidTank tank : inputFluids.getFluidTanks()) {
            FluidStack fluid = tank.getFluid();
            long energyPerMillibucket = plasmaEnergyPerMillibucket(fluid);
            if (fluid == null || energyPerMillibucket <= 0L) continue;

            long unitsNeeded = ceilDiv(needed, energyPerMillibucket);
            int amount = (int) Math.min(fluid.amount, Math.min(unitsNeeded, Integer.MAX_VALUE));
            if (amount <= 0) continue;
            FluidStack drained = tank.drain(amount, true);
            if (drained == null || drained.amount <= 0) continue;

            storedPlasmaEnergy = saturatingAdd(storedPlasmaEnergy,
                    saturatingMultiply(energyPerMillibucket, drained.amount));
            return true;
        }
        return false;
    }

    private long availablePlasmaEnergy() {
        long available = storedPlasmaEnergy;
        for (IFluidTank tank : inputFluids.getFluidTanks()) {
            FluidStack fluid = tank.getFluid();
            long energyPerMillibucket = plasmaEnergyPerMillibucket(fluid);
            if (fluid != null && energyPerMillibucket > 0L) {
                available = saturatingAdd(available, saturatingMultiply(energyPerMillibucket, fluid.amount));
            }
        }
        return available;
    }

    private static long plasmaEnergyPerMillibucket(@Nullable FluidStack fluid) {
        if (fluid == null || fluid.amount <= 0) return 0L;
        Recipe recipe = RecipeMaps.PLASMA_GENERATOR_FUELS.findRecipe(Long.MAX_VALUE, Collections.emptyList(),
                Collections.singletonList(fluid));
        if (recipe == null || recipe.getFluidInputs().isEmpty()) return 0L;

        GTRecipeInput input = recipe.getFluidInputs().getFirst();
        if (!input.acceptsFluid(fluid) || input.getAmount() <= 0 || recipe.getEUt() >= 0L) return 0L;
        return Math.max(1L, saturatingMultiply(-recipe.getEUt(), recipe.getDuration()) / input.getAmount());
    }

    private static long ceilDiv(long dividend, long divisor) {
        return dividend / divisor + (dividend % divisor == 0L ? 0L : 1L);
    }

    private static long saturatingAdd(long first, long second) {
        if (first >= Long.MAX_VALUE - second) return Long.MAX_VALUE;
        return first + second;
    }

    private static long saturatingMultiply(long first, long second) {
        if (first == 0L || second == 0L) return 0L;
        if (first > Long.MAX_VALUE / second) return Long.MAX_VALUE;
        return first * second;
    }
}
