package applygray.mattermanipulator.building;

import java.util.Objects;

import applygray.mattermanipulator.state.ManipulatorTransform;

import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.block.properties.IProperty;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.world.World;
import net.minecraftforge.common.util.Constants;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.IFluidBlock;

/**
 * Immutable one-block material specification.
 *
 * <p>The specification keeps the item NBT that is safe to represent on the item itself. Tile configuration is
 * deliberately not stored here; it belongs to a target building adapter.</p>
 */
public final class BlockSpec {

    private static final String KEY_AIR = "Air";
    private static final String KEY_STACK = "Stack";
    private static final String KEY_FLUID = "Fluid";
    private static final BlockSpec AIR = new BlockSpec(ItemStack.EMPTY);

    private final ItemStack template;
    private final FluidStack fluid;

    private BlockSpec(ItemStack template) {
        if (template.isEmpty()) {
            this.template = ItemStack.EMPTY;
        } else {
            this.template = template.copy();
            this.template.setCount(1);
        }
        this.fluid = null;
    }

    private BlockSpec(FluidStack fluid) {
        this.template = ItemStack.EMPTY;
        this.fluid = fluid.copy();
        if (this.fluid.amount <= 0) throw new IllegalArgumentException("fluid amount must be positive");
    }

    public static BlockSpec air() {
        return AIR;
    }

    public static BlockSpec of(ItemStack stack) {
        return stack == null || stack.isEmpty() ? air() : new BlockSpec(stack);
    }

    public static BlockSpec ofFluid(FluidStack stack) {
        return stack == null || stack.amount <= 0 ? air() : new BlockSpec(stack);
    }

    /**
     * Converts a persisted 1.12.2 block state into the item form a placement adapter can safely consume.
     *
     * <p>This intentionally does not serialize TileEntity data. Adapters which support configurable blocks own a
     * separate capture payload for that state.</p>
     */
    public static BlockSpec fromState(IBlockState state) {
        if (state == null || state.getBlock() == Blocks.AIR) return air();

        Fluid fluid = state.getBlock() instanceof IFluidBlock fluidBlock ? fluidBlock.getFluid()
                : FluidRegistry.lookupFluidForBlock(state.getBlock());
        if (fluid != null) return ofFluid(new FluidStack(fluid, 1000));

        Item item = Item.getItemFromBlock(state.getBlock());
        if (item == null || item == Items.AIR) return air();
        return of(new ItemStack(item, 1, state.getBlock().getMetaFromState(state)));
    }

    /**
     * Resolves the material a crosshair hit stands for, including tile-backed blocks.
     *
     * <p>A block state alone cannot identify a tile-backed block: every AE2 cable, bus and terminal shares
     * {@code ae2:cable_bus} — a block with no item form at all — and every GregTech machine shares one block whose
     * state carries no meta. The vanilla pick contract is asked first, then the block's own item form, and
     * {@link #fromState(IBlockState)} stays the fallback for ordinary blocks. A miss resolves to air, which is the
     * canonical way to select air.</p>
     */
    @SuppressWarnings("deprecation")
    public static BlockSpec fromPickBlock(World world, EntityPlayer player, RayTraceResult hit) {
        if (world == null || hit == null || hit.typeOfHit != RayTraceResult.Type.BLOCK) return air();
        BlockPos position = hit.getBlockPos();
        if (position == null) return air();

        IBlockState state = world.getBlockState(position);
        BlockSpec fromState = fromState(state);
        if (fromState.isFluid() || !state.getBlock().hasTileEntity(state)) return fromState;

        // Both contracts read the tile, and a foreign block may not expect a hit rebuilt outside its own ray trace.
        ItemStack picked = pickBlockOrEmpty(state, hit, world, position, player);
        if (picked.isEmpty()) picked = itemOrEmpty(state, world, position);
        return picked.isEmpty() ? fromState : of(picked);
    }

    private static ItemStack pickBlockOrEmpty(IBlockState state, RayTraceResult hit, World world, BlockPos position,
                                              EntityPlayer player) {
        try {
            return state.getBlock().getPickBlock(state, hit, world, position, player);
        } catch (RuntimeException exception) {
            return ItemStack.EMPTY;
        }
    }

    private static ItemStack itemOrEmpty(IBlockState state, World world, BlockPos position) {
        try {
            return state.getBlock().getItem(world, position, state);
        } catch (RuntimeException exception) {
            return ItemStack.EMPTY;
        }
    }

    public boolean isAir() {
        return template.isEmpty() && fluid == null;
    }

    public boolean isFluid() {
        return fluid != null;
    }

    public FluidStack fluidStack() {
        return fluid == null ? null : fluid.copy();
    }

    /** Applies a target three-dimensional transform to portable facing and axis properties. */
    public BlockSpec transformed(ManipulatorTransform transform) {
        Objects.requireNonNull(transform, "transform");
        if (fluid != null) return this;
        if (template.isEmpty()) return air();

        IBlockState state = stateFor(template);
        if (state == null) return this;
        IBlockState transformed = state;
        for (IProperty<?> property : state.getPropertyKeys()) {
            Comparable<?> value = state.getValue(property);
            if (value instanceof EnumFacing facing) {
                transformed = withTransformedProperty(transformed, property, transform.apply(facing));
            } else if (value instanceof EnumFacing.Axis axis) {
                transformed = withTransformedProperty(transformed, property, transform.apply(axis));
            }
        }
        return fromState(transformed);
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private static IBlockState withTransformedProperty(IBlockState state, IProperty property, Comparable value) {
        if (!property.getAllowedValues().contains(value)) {
            throw new IllegalArgumentException("The block state cannot represent transformed property " +
                    property.getName() + '=' + value);
        }
        return state.withProperty(property, value);
    }

    public ItemStack toStack() {
        return template.isEmpty() ? ItemStack.EMPTY : template.copy();
    }

    /** Resolves this 1.12 item-and-metadata representation, or returns {@code null} when it is not a block state. */
    public IBlockState toBlockState() {
        return stateFor(template);
    }

    /**
     * Tests whether a live world state already represents this placement target.
     *
     * <p>This is intentionally less strict than {@link #equals(Object)}: item NBT and fluid amount are material
     * accounting details which cannot be reconstructed from an ordinary block state.</p>
     */
    public boolean matchesWorldState(IBlockState state) {
        if (state == null) return false;
        if (isAir()) return state.getBlock() == Blocks.AIR;
        if (fluid != null) {
            Fluid stateFluid = state.getBlock() instanceof IFluidBlock fluidBlock ? fluidBlock.getFluid()
                    : FluidRegistry.lookupFluidForBlock(state.getBlock());
            return stateFluid != null && stateFluid == fluid.getFluid();
        }
        IBlockState targetState = toBlockState();
        return targetState != null && targetState.equals(state);
    }

    public String sortKey() {
        if (fluid != null) return "fluid:" + fluid.getFluid().getName() + "@" + fluid.amount + ":" + fluid.tag;
        if (template.isEmpty()) return "minecraft:air";
        Item item = template.getItem();
        return item.getRegistryName() + "@" + template.getMetadata() + ":" +
                (template.hasTagCompound() ? template.getTagCompound() : "");
    }

    public NBTTagCompound writeToNbt() {
        NBTTagCompound data = new NBTTagCompound();
        if (fluid != null) {
            data.setTag(KEY_FLUID, fluid.writeToNBT(new NBTTagCompound()));
        } else if (template.isEmpty()) {
            data.setBoolean(KEY_AIR, true);
        } else {
            data.setTag(KEY_STACK, template.writeToNBT(new NBTTagCompound()));
        }
        return data;
    }

    public static BlockSpec readFromNbt(NBTTagCompound data) {
        if (data == null || data.getBoolean(KEY_AIR)) {
            return air();
        }
        if (data.hasKey(KEY_FLUID, Constants.NBT.TAG_COMPOUND)) {
            return ofFluid(FluidStack.loadFluidStackFromNBT(data.getCompoundTag(KEY_FLUID)));
        }
        if (!data.hasKey(KEY_STACK, Constants.NBT.TAG_COMPOUND)) return air();
        return of(new ItemStack(data.getCompoundTag(KEY_STACK)));
    }

    @SuppressWarnings("deprecation")
    private static IBlockState stateFor(ItemStack stack) {
        try {
            net.minecraft.block.Block block = net.minecraft.block.Block.getBlockFromItem(stack.getItem());
            return block == Blocks.AIR ? null : block.getStateFromMeta(stack.getMetadata());
        } catch (RuntimeException exception) {
            return null;
        }
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof BlockSpec spec)) return false;
        return isFluid() || spec.isFluid()
                ? isFluid() && spec.isFluid() && fluid.isFluidEqual(spec.fluid) && fluid.amount == spec.fluid.amount
                : ItemStack.areItemStacksEqual(template, spec.template);
    }

    @Override
    public int hashCode() {
        if (isAir()) return 0;
        if (fluid != null) return Objects.hash(fluid.getFluid().getName(), fluid.amount, fluid.tag);
        return Objects.hash(template.getItem().getRegistryName(), template.getMetadata(), template.getTagCompound());
    }

    @Override
    public String toString() {
        return sortKey();
    }
}
