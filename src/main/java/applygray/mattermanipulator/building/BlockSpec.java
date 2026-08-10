package applygray.mattermanipulator.building;

import java.util.Objects;

import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.Mirror;
import net.minecraft.util.Rotation;
import net.minecraftforge.common.util.Constants;

/**
 * Immutable one-block material specification.
 *
 * <p>The specification keeps the item NBT that is safe to represent on the item itself. Tile configuration is
 * deliberately not stored here; it belongs to a target building adapter.</p>
 */
public final class BlockSpec {

    private static final String KEY_AIR = "Air";
    private static final String KEY_STACK = "Stack";
    private static final BlockSpec AIR = new BlockSpec(ItemStack.EMPTY);

    private final ItemStack template;

    private BlockSpec(ItemStack template) {
        if (template.isEmpty()) {
            this.template = ItemStack.EMPTY;
        } else {
            this.template = template.copy();
            this.template.setCount(1);
        }
    }

    public static BlockSpec air() {
        return AIR;
    }

    public static BlockSpec of(ItemStack stack) {
        return stack == null || stack.isEmpty() ? air() : new BlockSpec(stack);
    }

    /**
     * Converts a persisted 1.12.2 block state into the item form a placement adapter can safely consume.
     *
     * <p>This intentionally does not serialize TileEntity data. Adapters which support configurable blocks own a
     * separate capture payload for that state.</p>
     */
    public static BlockSpec fromState(IBlockState state) {
        if (state == null || state.getBlock() == Blocks.AIR) return air();

        Item item = Item.getItemFromBlock(state.getBlock());
        if (item == null || item == Items.AIR) return air();
        return of(new ItemStack(item, 1, state.getBlock().getMetaFromState(state)));
    }

    public boolean isAir() {
        return template.isEmpty();
    }

    /** Applies a target 1.12.2 structure transform while preserving the resulting item metadata. */
    public BlockSpec transformed(Mirror mirror, Rotation rotation) {
        Objects.requireNonNull(mirror, "mirror");
        Objects.requireNonNull(rotation, "rotation");
        if (template.isEmpty()) return air();

        IBlockState state = stateFor(template);
        return state == null ? this : fromState(state.withMirror(mirror).withRotation(rotation));
    }

    public ItemStack toStack() {
        return template.isEmpty() ? ItemStack.EMPTY : template.copy();
    }

    public String sortKey() {
        if (template.isEmpty()) return "minecraft:air";
        Item item = template.getItem();
        return item.getRegistryName() + "@" + template.getMetadata() + ":" +
                (template.hasTagCompound() ? template.getTagCompound() : "");
    }

    public NBTTagCompound writeToNbt() {
        NBTTagCompound data = new NBTTagCompound();
        if (template.isEmpty()) {
            data.setBoolean(KEY_AIR, true);
        } else {
            data.setTag(KEY_STACK, template.writeToNBT(new NBTTagCompound()));
        }
        return data;
    }

    public static BlockSpec readFromNbt(NBTTagCompound data) {
        if (data == null || data.getBoolean(KEY_AIR) || !data.hasKey(KEY_STACK, Constants.NBT.TAG_COMPOUND)) {
            return air();
        }
        return of(new ItemStack(data.getCompoundTag(KEY_STACK)));
    }

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
        return ItemStack.areItemStacksEqual(template, spec.template);
    }

    @Override
    public int hashCode() {
        if (template.isEmpty()) return 0;
        return Objects.hash(template.getItem().getRegistryName(), template.getMetadata(), template.getTagCompound());
    }

    @Override
    public String toString() {
        return sortKey();
    }
}
