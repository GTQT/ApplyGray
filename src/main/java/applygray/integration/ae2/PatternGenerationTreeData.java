package applygray.integration.ae2;

import ae2.container.guisync.PacketWritable;
import ae2.integration.data.CraftingTreeStackRegistry;
import ae2.integration.data.LiteCraftTreeNode;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

/** Immutable, synchronized state for the independent RecipeMap pattern-generation tree. */
public final class PatternGenerationTreeData implements PacketWritable {

    public enum Status {
        IDLE,
        GENERATING,
        READY,
        UNAVAILABLE,
        FAILED
    }

    private final Status status;
    private final LiteCraftTreeNode root;

    public PatternGenerationTreeData(ByteBuf data) {
        Status[] values = Status.values();
        int ordinal = data.readUnsignedByte();
        status = ordinal < values.length ? values[ordinal] : Status.FAILED;
        if (!data.readBoolean()) {
            root = null;
            return;
        }

        LiteCraftTreeNode decodedRoot;
        try {
            CraftingTreeStackRegistry.DecodeLimits limits = new CraftingTreeStackRegistry.DecodeLimits();
            CraftingTreeStackRegistry stacks = new CraftingTreeStackRegistry();
            stacks.read(data, limits);
            decodedRoot = LiteCraftTreeNode.fromBuffer(data, stacks, null, limits, 0);
        } catch (RuntimeException ignored) {
            decodedRoot = null;
        }
        root = decodedRoot;
    }

    private PatternGenerationTreeData(Status status, LiteCraftTreeNode root) {
        this.status = status;
        this.root = root;
    }

    public static PatternGenerationTreeData idle() {
        return new PatternGenerationTreeData(Status.IDLE, null);
    }

    public static PatternGenerationTreeData generating() {
        return new PatternGenerationTreeData(Status.GENERATING, null);
    }

    public static PatternGenerationTreeData ready(LiteCraftTreeNode root) {
        return new PatternGenerationTreeData(Status.READY, root);
    }

    public static PatternGenerationTreeData unavailable() {
        return new PatternGenerationTreeData(Status.UNAVAILABLE, null);
    }

    public static PatternGenerationTreeData failed() {
        return new PatternGenerationTreeData(Status.FAILED, null);
    }

    public Status getStatus() {
        return status;
    }

    public LiteCraftTreeNode getRoot() {
        return root;
    }

    @Override
    public void writeToPacket(ByteBuf data) {
        data.writeByte(status.ordinal());
        data.writeBoolean(root != null);
        if (root == null) return;

        CraftingTreeStackRegistry stacks = new CraftingTreeStackRegistry();
        ByteBuf treeData = Unpooled.buffer();
        root.writeToBuffer(treeData, stacks);
        stacks.write(data);
        data.writeBytes(treeData);
    }
}
