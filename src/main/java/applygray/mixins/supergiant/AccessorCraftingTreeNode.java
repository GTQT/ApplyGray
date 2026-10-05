package applygray.mixins.supergiant;

import ae2.crafting.CraftingTreeProcess;
import ae2.crafting.CraftingTreeNode;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Exposes a tree node's built child processes so the preview bypass can inspect its input subtree. */
@Mixin(value = CraftingTreeNode.class, remap = false)
public interface AccessorCraftingTreeNode {

    /** Returns the child processes built by {@code buildChildPatterns()}, or {@code null} if not built yet. */
    @Accessor("nodes")
    ObjectArrayList<CraftingTreeProcess> applygray$getChildProcesses();

    /** Builds the child process list once; later calls are no-ops. */
    @Invoker("buildChildPatterns")
    void applygray$buildChildPatterns();
}
