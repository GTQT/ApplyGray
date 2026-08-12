package applygray.client.mattermanipulator;

import java.util.ArrayList;
import java.util.List;

import applygray.mattermanipulator.item.ItemMatterManipulator;
import applygray.mattermanipulator.planning.CopyPlan;
import applygray.mattermanipulator.planning.CopyPlanner;
import applygray.mattermanipulator.planning.CopyTransform;
import applygray.mattermanipulator.planning.GeometryPlan;
import applygray.mattermanipulator.planning.GeometryPlanException;
import applygray.mattermanipulator.planning.GeometryPlanner;
import applygray.mattermanipulator.planning.GeometrySelection;
import applygray.mattermanipulator.state.ManipulatorLocation;
import applygray.mattermanipulator.state.ManipulatorPlaceMode;
import applygray.mattermanipulator.state.ManipulatorShape;
import applygray.mattermanipulator.state.ManipulatorState;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import org.lwjgl.opengl.GL11;

/**
 * Client-only selection and operation preview for the target-native manipulator workflow.
 *
 * <p>Plans are rebuilt only when the held stack's persistent state or world dimension changes. Detailed voxel boxes
 * are deliberately capped; large selections show their exact bounds instead of allocating a new million-entry plan
 * every rendered frame.</p>
 */
public final class MatterManipulatorPreviewRenderer {

    private static final int MAX_DETAILED_VOXELS = 512;
    private static boolean initialized;
    private static ManipulatorState cachedState;
    private static int cachedDimension = Integer.MIN_VALUE;
    private static Preview cachedPreview = Preview.empty();

    private MatterManipulatorPreviewRenderer() {}

    public static void initialize() {
        if (initialized) return;
        initialized = true;
        MinecraftForge.EVENT_BUS.register(MatterManipulatorPreviewRenderer.class);
    }

    @SubscribeEvent
    public static void renderWorldLast(RenderWorldLastEvent event) {
        Minecraft minecraft = Minecraft.getMinecraft();
        EntityPlayerSP player = minecraft.player;
        World world = minecraft.world;
        if (player == null || world == null) return;

        ItemStack stack = heldManipulator(player);
        if (stack.isEmpty() || !(stack.getItem() instanceof ItemMatterManipulator manipulator)) return;

        ManipulatorState state = manipulator.state(stack);
        Preview preview = preview(world, state);
        if (preview.boxes.isEmpty()) return;

        RenderManager renderManager = minecraft.getRenderManager();
        GlStateManager.pushMatrix();
        GlStateManager.translate(-renderManager.viewerPosX, -renderManager.viewerPosY, -renderManager.viewerPosZ);
        GlStateManager.disableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.disableCull();
        GlStateManager.disableDepth();
        GlStateManager.depthMask(false);
        GlStateManager.glLineWidth(2.0F);

        for (PreviewBox box : preview.boxes) {
            if (box.filled) drawGhostBox(box.bounds.grow(0.006D), box.red, box.green, box.blue, box.alpha);
        }

        for (PreviewBox box : preview.boxes) {
            if (!box.filled) {
                RenderGlobal.drawSelectionBoundingBox(box.bounds.grow(0.002D), box.red, box.green, box.blue, box.alpha);
            }
        }

        GlStateManager.glLineWidth(1.0F);
        GlStateManager.depthMask(true);
        GlStateManager.enableDepth();
        GlStateManager.disableBlend();
        GlStateManager.enableCull();
        GlStateManager.enableTexture2D();
        GlStateManager.popMatrix();
    }

    private static ItemStack heldManipulator(EntityPlayerSP player) {
        ItemStack mainHand = player.getHeldItemMainhand();
        if (mainHand.getItem() instanceof ItemMatterManipulator) return mainHand;
        ItemStack offHand = player.getHeldItemOffhand();
        return offHand.getItem() instanceof ItemMatterManipulator ? offHand : ItemStack.EMPTY;
    }

    private static Preview preview(World world, ManipulatorState state) {
        int dimension = world.provider.getDimension();
        if (cachedState != null && cachedDimension == dimension && cachedState.equals(state)) return cachedPreview;

        cachedState = ManipulatorState.readFromNbt(state.writeToNbt());
        cachedDimension = dimension;
        cachedPreview = buildPreview(dimension, cachedState);
        return cachedPreview;
    }

    private static Preview buildPreview(int dimension, ManipulatorState state) {
        List<PreviewBox> boxes = new ArrayList<>();
        addSelection(boxes, state.selectionA(), dimension, 0.20F, 0.75F, 1.00F);
        addSelection(boxes, state.selectionB(), dimension, 1.00F, 0.65F, 0.15F);
        addSelection(boxes, state.selectionC(), dimension, 0.85F, 0.35F, 1.00F);

        try {
            switch (state.placeMode()) {
                case GEOMETRY -> addGeometryPreview(boxes, state, dimension);
                case CABLES -> addCablePreview(boxes, state, dimension);
                case COPYING, MOVING -> addCopyPreview(boxes, state, dimension);
                case EXCHANGING -> addRegionPreview(boxes, state.selectionA(), state.selectionB(), dimension,
                        0.95F, 0.35F, 0.20F);
            }
        } catch (GeometryPlanException ignored) {
            // The server remains authoritative. Incomplete or oversize client selections only omit their derived box.
        }
        return new Preview(boxes);
    }

    private static void addGeometryPreview(List<PreviewBox> boxes, ManipulatorState state, int dimension) {
        GeometrySelection selection = state.geometrySelection();
        if (!selection.isComplete() || selection.a().dimension() != dimension) return;
        GeometryPlan plan = GeometryPlanner.plan(selection, MAX_DETAILED_VOXELS + 1);
        // GT5U's A/B volume hint is useful for volumetric shapes, but for a line it otherwise looks like
        // every block inside the bounding box will be built. Keep the ghost aligned with the actual plan.
        if (selection.shape() == ManipulatorShape.LINE) {
            addRegionPreview(boxes, plan.operations().stream().map(operation -> operation.location().position()).toList(),
                    0.15F, 0.60F, 0.75F, 0.90F);
        } else {
            List<BlockPos> anchors = new ArrayList<>();
            anchors.add(selection.a().position());
            anchors.add(selection.b().position());
            if (selection.c() != null) anchors.add(selection.c().position());
            addRegionPreview(boxes, anchors, 0.15F, 0.60F, 0.75F, 0.90F);
        }
        addOperationPreview(boxes, plan.operations().stream().map(operation -> operation.location().position()).toList(),
                0.25F, 0.95F, 0.85F);
    }

    private static void addCablePreview(List<PreviewBox> boxes, ManipulatorState state, int dimension) {
        ManipulatorLocation a = state.selectionA();
        ManipulatorLocation b = state.selectionB();
        if (!sameDimension(dimension, a, b)) return;
        BlockPos pinned = GeometryPlanner.pinToAxes(a.position(), b.position());
        addRegionPreview(boxes, List.of(a.position(), pinned), 0.15F, 0.60F, 0.75F, 0.90F);
        GeometryPlan plan = GeometryPlanner.plan(new GeometrySelection(ManipulatorShape.LINE, a,
                new ManipulatorLocation(dimension, pinned), null), MAX_DETAILED_VOXELS + 1);
        addOperationPreview(boxes, plan.operations().stream().map(operation -> operation.location().position()).toList(),
                0.25F, 0.95F, 0.85F);
    }

    private static void addCopyPreview(List<PreviewBox> boxes, ManipulatorState state, int dimension) {
        ManipulatorLocation a = state.selectionA();
        ManipulatorLocation b = state.selectionB();
        ManipulatorLocation c = state.selectionC();
        if (!sameDimension(dimension, a, b, c)) return;

        addRegionPreview(boxes, a, b, dimension, 0.20F, 0.75F, 1.00F);
        CopyPlan plan = CopyPlanner.plan(a, b, c, new CopyTransform(state.copyTransform(),
                state.copyRepeatX(), state.copyRepeatY(), state.copyRepeatZ()), MAX_DETAILED_VOXELS + 1);
        List<BlockPos> targets = plan.operations().stream().map(operation -> operation.target()).toList();
        addRegionPreview(boxes, targets, 0.75F, 0.50F, 0.15F, 0.90F);
        addOperationPreview(boxes, targets, 0.95F,
                0.35F, 0.20F);
    }

    private static void addOperationPreview(List<PreviewBox> boxes, List<BlockPos> positions, float red, float green,
                                            float blue) {
        if (positions.size() > MAX_DETAILED_VOXELS) {
            boxes.add(new PreviewBox(bounds(positions), red, green, blue, 0.85F, false));
            return;
        }
        for (BlockPos position : positions) {
            boxes.add(new PreviewBox(new AxisAlignedBB(position), red, green, blue, 0.60F, false));
        }
    }

    private static void addSelection(List<PreviewBox> boxes, ManipulatorLocation location, int dimension, float red,
                                     float green, float blue) {
        if (location == null || location.dimension() != dimension) return;
        boxes.add(new PreviewBox(new AxisAlignedBB(location.position()), red, green, blue, 0.24F, true));
    }

    private static void addRegionPreview(List<PreviewBox> boxes, ManipulatorLocation a, ManipulatorLocation b,
                                         int dimension, float red, float green, float blue) {
        if (!sameDimension(dimension, a, b)) return;
        addRegionPreview(boxes, List.of(a.position(), b.position()), red, green, blue, 0.85F);
    }

    private static void addRegionPreview(List<PreviewBox> boxes, List<BlockPos> positions, float red, float green,
                                         float blue, float alpha) {
        if (positions.isEmpty()) return;
        boxes.add(new PreviewBox(bounds(positions), red, green, blue, 0.25F, true));
    }

    private static boolean sameDimension(int dimension, ManipulatorLocation... locations) {
        for (ManipulatorLocation location : locations) {
            if (location == null || location.dimension() != dimension) return false;
        }
        return true;
    }

    private static AxisAlignedBB bounds(List<BlockPos> positions) {
        if (positions.isEmpty()) return new AxisAlignedBB(BlockPos.ORIGIN);
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (BlockPos position : positions) {
            minX = Math.min(minX, position.getX());
            minY = Math.min(minY, position.getY());
            minZ = Math.min(minZ, position.getZ());
            maxX = Math.max(maxX, position.getX());
            maxY = Math.max(maxY, position.getY());
            maxZ = Math.max(maxZ, position.getZ());
        }
        return new AxisAlignedBB(minX, minY, minZ, maxX + 1D, maxY + 1D, maxZ + 1D);
    }

    private record Preview(List<PreviewBox> boxes) {

        private static Preview empty() {
            return new Preview(List.of());
        }
    }

    private static void drawGhostBox(AxisAlignedBB box, float red, float green, float blue, float alpha) {
        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder buffer = tessellator.getBuffer();
        buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
        face(buffer, box.minX, box.minY, box.minZ, box.maxX, box.minY, box.minZ,
                box.maxX, box.minY, box.maxZ, box.minX, box.minY, box.maxZ, red, green, blue, alpha);
        face(buffer, box.minX, box.maxY, box.maxZ, box.maxX, box.maxY, box.maxZ,
                box.maxX, box.maxY, box.minZ, box.minX, box.maxY, box.minZ, red, green, blue, alpha);
        face(buffer, box.minX, box.minY, box.maxZ, box.minX, box.minY, box.minZ,
                box.minX, box.maxY, box.minZ, box.minX, box.maxY, box.maxZ, red, green, blue, alpha);
        face(buffer, box.maxX, box.minY, box.minZ, box.maxX, box.minY, box.maxZ,
                box.maxX, box.maxY, box.maxZ, box.maxX, box.maxY, box.minZ, red, green, blue, alpha);
        face(buffer, box.minX, box.minY, box.minZ, box.minX, box.maxY, box.minZ,
                box.maxX, box.maxY, box.minZ, box.maxX, box.minY, box.minZ, red, green, blue, alpha);
        face(buffer, box.maxX, box.minY, box.maxZ, box.maxX, box.maxY, box.maxZ,
                box.minX, box.maxY, box.maxZ, box.minX, box.minY, box.maxZ, red, green, blue, alpha);
        tessellator.draw();
    }

    private static void face(BufferBuilder buffer,
                             double x1, double y1, double z1,
                             double x2, double y2, double z2,
                             double x3, double y3, double z3,
                             double x4, double y4, double z4,
                                   float red, float green, float blue, float alpha) {
        buffer.pos(x1, y1, z1).color(red, green, blue, alpha).endVertex();
        buffer.pos(x2, y2, z2).color(red, green, blue, alpha).endVertex();
        buffer.pos(x3, y3, z3).color(red, green, blue, alpha).endVertex();
        buffer.pos(x4, y4, z4).color(red, green, blue, alpha).endVertex();
    }

    private record PreviewBox(AxisAlignedBB bounds, float red, float green, float blue, float alpha, boolean filled) {}
}
