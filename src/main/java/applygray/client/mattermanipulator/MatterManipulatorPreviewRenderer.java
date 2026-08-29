package applygray.client.mattermanipulator;

import java.util.ArrayList;
import java.util.List;

import applygray.mattermanipulator.building.BlockSpec;
import applygray.mattermanipulator.building.GeometryConfiguration;
import applygray.mattermanipulator.config.MatterManipulatorConfig;
import applygray.mattermanipulator.item.ItemMatterManipulator;
import applygray.mattermanipulator.planning.BoundGeometryOperation;
import applygray.mattermanipulator.planning.BoundGeometryPlan;
import applygray.mattermanipulator.planning.CablePathPlanner;
import applygray.mattermanipulator.planning.CopyPlan;
import applygray.mattermanipulator.planning.CopyPlanner;
import applygray.mattermanipulator.planning.CopyPositionOperation;
import applygray.mattermanipulator.planning.CopyTransform;
import applygray.mattermanipulator.planning.GeometryPlan;
import applygray.mattermanipulator.planning.GeometryPlanBinder;
import applygray.mattermanipulator.planning.GeometryPlanException;
import applygray.mattermanipulator.planning.GeometryPlanner;
import applygray.mattermanipulator.planning.GeometrySelection;
import applygray.mattermanipulator.state.ManipulatorLocation;
import applygray.mattermanipulator.state.ManipulatorPlaceMode;
import applygray.mattermanipulator.state.ManipulatorSelectionActions;
import applygray.mattermanipulator.state.ManipulatorSelectionDimensions;
import applygray.mattermanipulator.state.ManipulatorShape;
import applygray.mattermanipulator.state.ManipulatorState;
import applygray.mattermanipulator.state.ManipulatorTier;
import applygray.mattermanipulator.util.ManipulatorTargeting;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
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

    private static final long VISIBILITY_REFRESH_INTERVAL_MS = 500L;

    private static boolean initialized;
    private static ManipulatorState cachedState;
    private static int cachedDimension = Integer.MIN_VALUE;
    private static BlockPos cachedPlayerBlock;
    private static Preview cachedPreview = Preview.empty();
    private static Preview visibilityPreview;
    private static List<PreviewBox> cachedVisibleBoxes = List.of();
    private static long nextVisibilityRefreshMillis;

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
        Preview preview = preview(world, player, state, manipulator.tier(), event.getPartialTicks());
        List<PreviewBox> visibleBoxes = visibleBoxes(world, preview);
        if (visibleBoxes.isEmpty()) return;

        RenderManager renderManager = minecraft.getRenderManager();
        GlStateManager.pushMatrix();
        GlStateManager.translate(-renderManager.viewerPosX, -renderManager.viewerPosY, -renderManager.viewerPosZ);
        GlStateManager.disableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.disableCull();
        if (MatterManipulatorConfig.hintsOnTop) GlStateManager.disableDepth();
        else GlStateManager.enableDepth();
        GlStateManager.depthMask(false);

        for (PreviewBox box : visibleBoxes) {
            drawGhostBox(box.bounds.grow(0.006D), box.red, box.green, box.blue, box.alpha);
        }

        GlStateManager.depthMask(true);
        GlStateManager.enableDepth();
        GlStateManager.disableBlend();
        GlStateManager.enableCull();
        GlStateManager.enableTexture2D();
        GlStateManager.popMatrix();
    }

    @SubscribeEvent
    public static void renderSelectionSize(RenderGameOverlayEvent.Post event) {
        if (event.getType() != RenderGameOverlayEvent.ElementType.HOTBAR) return;

        Minecraft minecraft = Minecraft.getMinecraft();
        EntityPlayerSP player = minecraft.player;
        World world = minecraft.world;
        if (player == null || world == null || minecraft.gameSettings.hideGUI) return;

        ItemStack stack = heldManipulator(player);
        if (stack.isEmpty() || !(stack.getItem() instanceof ItemMatterManipulator manipulator)) return;

        ManipulatorState state = withMovingTarget(world, player, manipulator.state(stack), event.getPartialTicks());
        ManipulatorSelectionDimensions dimensions = ManipulatorSelectionDimensions.from(state,
                world.provider.getDimension());
        if (dimensions == null) return;

        String text = I18n.format("applygray.matter_manipulator.selection.dimensions",
                Long.toString(dimensions.x()), Long.toString(dimensions.y()), Long.toString(dimensions.z()),
                dimensions.volume().toString());
        drawCenteredHudText(minecraft, event, text);
    }

    private static void drawCenteredHudText(Minecraft minecraft, RenderGameOverlayEvent event, String text) {
        int textWidth = minecraft.fontRenderer.getStringWidth(text);
        int availableWidth = Math.max(1, event.getResolution().getScaledWidth() - 8);
        float scale = Math.min(1.0F, (float) availableWidth / textWidth);
        float centerX = event.getResolution().getScaledWidth() * 0.5F;
        int y = event.getResolution().getScaledHeight() - 79;

        GlStateManager.pushMatrix();
        GlStateManager.translate(centerX, y, 0.0F);
        GlStateManager.scale(scale, scale, 1.0F);
        minecraft.fontRenderer.drawStringWithShadow(text, -textWidth * 0.5F, 0.0F, 0xFFFFFF);
        GlStateManager.popMatrix();
    }

    private static ItemStack heldManipulator(EntityPlayerSP player) {
        ItemStack mainHand = player.getHeldItemMainhand();
        if (mainHand.getItem() instanceof ItemMatterManipulator) return mainHand;
        ItemStack offHand = player.getHeldItemOffhand();
        return offHand.getItem() instanceof ItemMatterManipulator ? offHand : ItemStack.EMPTY;
    }

    private static Preview preview(World world, EntityPlayerSP player, ManipulatorState state, ManipulatorTier tier,
                                   float partialTicks) {
        int dimension = world.provider.getDimension();
        ManipulatorState effectiveState = withMovingTarget(world, player, state, partialTicks);
        BlockPos playerBlock = player.getPosition();
        if (cachedState != null && cachedDimension == dimension && playerBlock.equals(cachedPlayerBlock) &&
                cachedState.equals(effectiveState)) return cachedPreview;

        cachedState = ManipulatorState.readFromNbt(effectiveState.writeToNbt());
        cachedDimension = dimension;
        cachedPlayerBlock = playerBlock;
        cachedPreview = colorByRange(buildPreview(world, dimension, cachedState), player, tier.maximumRange());
        return cachedPreview;
    }

    private static List<PreviewBox> visibleBoxes(World world, Preview preview) {
        long now = System.currentTimeMillis();
        if (visibilityPreview == preview && now < nextVisibilityRefreshMillis) return cachedVisibleBoxes;

        List<PreviewBox> candidates = preview.visibleBoxes(now);
        List<PreviewBox> visible = new ArrayList<>(candidates.size());
        for (PreviewBox box : candidates) {
            if (!box.isSatisfiedBy(world)) visible.add(box);
        }
        visibilityPreview = preview;
        cachedVisibleBoxes = List.copyOf(visible);
        nextVisibilityRefreshMillis = now + VISIBILITY_REFRESH_INTERVAL_MS;
        return cachedVisibleBoxes;
    }

    private static Preview colorByRange(Preview preview, EntityPlayerSP player, int maximumRange) {
        if (maximumRange < 0) return preview;
        double rangeSquared = (double) maximumRange * maximumRange;
        List<PreviewBox> recolored = new ArrayList<>(preview.boxes.size());
        for (PreviewBox box : preview.boxes) {
            double centerX = (box.bounds.minX + box.bounds.maxX) * 0.5D;
            double centerY = (box.bounds.minY + box.bounds.maxY) * 0.5D;
            double centerZ = (box.bounds.minZ + box.bounds.maxZ) * 0.5D;
            boolean inRange = player.getDistanceSq(centerX, centerY, centerZ) <= rangeSquared;
            recolored.add(inRange ? box : box.withColor(1.0F, 0.12F, 0.12F));
        }
        List<PreviewBox> recoloredTransient = new ArrayList<>(preview.transientBoxes.size());
        for (PreviewBox box : preview.transientBoxes) {
            double centerX = (box.bounds.minX + box.bounds.maxX) * 0.5D;
            double centerY = (box.bounds.minY + box.bounds.maxY) * 0.5D;
            double centerZ = (box.bounds.minZ + box.bounds.maxZ) * 0.5D;
            boolean inRange = player.getDistanceSq(centerX, centerY, centerZ) <= rangeSquared;
            recoloredTransient.add(inRange ? box : box.withColor(1.0F, 0.12F, 0.12F));
        }
        return new Preview(recolored, recoloredTransient, preview.transientExpiresAtMillis);
    }

    /**
     * GT5 keeps the point currently being selected attached to the player's crosshair until the next click
     * confirms it. This is a client-only projection; the persistent/server state is changed only by the click.
     */
    private static ManipulatorState withMovingTarget(World world, EntityPlayerSP player, ManipulatorState state,
                                                      float partialTicks) {
        if (!state.pendingAction().selectsCoordinates()) return state;

        BlockPos target = ManipulatorTargeting.lookingAt(player, partialTicks);

        ManipulatorState preview = ManipulatorState.readFromNbt(state.writeToNbt());
        ManipulatorLocation location = ManipulatorLocation.fromWorld(world, target);
        ManipulatorSelectionActions.projectCoordinates(preview, location);
        return preview;
    }

    private static Preview buildPreview(World world, int dimension, ManipulatorState state) {
        List<PreviewBox> boxes = new ArrayList<>();
        List<PreviewBox> transientBoxes = new ArrayList<>();
        addSelection(boxes, state.selectionA(), dimension, 0.20F, 0.75F, 1.00F);
        addSelection(boxes, state.selectionB(), dimension, 1.00F, 0.65F, 0.15F);
        addSelection(boxes, state.selectionC(), dimension, 0.85F, 0.35F, 1.00F);

        try {
            switch (state.placeMode()) {
                case GEOMETRY -> addGeometryPreview(boxes, state, dimension);
                case CABLES -> addCablePreview(boxes, state, dimension);
                case COPYING, MOVING -> addCopyPreview(boxes, world, state, dimension);
                case EXCHANGING -> addRegionPreview(boxes, state.selectionA(), state.selectionB(), dimension,
                        0.95F, 0.35F, 0.20F);
            }
        } catch (GeometryPlanException ignored) {
            // Keep a visible error state instead of silently dropping the derived preview. The server remains
            // authoritative and will reject the same invalid plan at execution time.
            addRegionPreview(transientBoxes, state.selectionA(), state.selectionB(), dimension, 1.00F, 0.10F,
                    0.10F);
        }
        long expiry = transientBoxes.isEmpty() || MatterManipulatorConfig.statusExpirationSeconds == 0
                ? 0L
                : System.currentTimeMillis() + MatterManipulatorConfig.statusExpirationSeconds * 1_000L;
        return new Preview(boxes, transientBoxes, expiry);
    }

    private static void addGeometryPreview(List<PreviewBox> boxes, ManipulatorState state, int dimension) {
        GeometrySelection selection = state.geometrySelection();
        if (!selection.isComplete() || selection.a().dimension() != dimension) return;
        GeometryPlan plan = GeometryPlanner.plan(selection, maxDetailedVoxels() + 1);
        BoundGeometryPlan bound = GeometryPlanBinder.bind(plan, state.geometryConfiguration());
        List<PreviewTarget> targets = bound.operations().stream()
                .map(MatterManipulatorPreviewRenderer::previewTarget)
                .toList();
        // GT5U's A/B volume hint is useful for volumetric shapes, but for a line it otherwise looks like
        // every block inside the bounding box will be built. Keep the ghost aligned with the actual plan.
        if (selection.shape() == ManipulatorShape.LINE) {
            addTrackedRegionPreview(boxes,
                    plan.operations().stream().map(operation -> operation.location().position()).toList(), targets,
                    0.15F, 0.60F, 0.75F);
        } else {
            List<BlockPos> anchors = new ArrayList<>();
            anchors.add(selection.a().position());
            if (selection.shape() == ManipulatorShape.CYLINDER) {
                BlockPos pinnedB = GeometryPlanner.pinToPlanes(selection.a().position(), selection.b().position());
                anchors.add(pinnedB);
                anchors.add(GeometryPlanner.pinToLine(selection.a().position(), pinnedB,
                        selection.c().position()));
            } else {
                anchors.add(selection.b().position());
            }
            addTrackedRegionPreview(boxes, anchors, targets, 0.15F, 0.60F, 0.75F);
        }
        addOperationPreview(boxes, targets, 0.25F, 0.95F, 0.85F);
    }

    private static void addCablePreview(List<PreviewBox> boxes, ManipulatorState state, int dimension) {
        ManipulatorLocation a = state.selectionA();
        ManipulatorLocation b = state.selectionB();
        if (!sameDimension(dimension, a, b)) return;

        List<BlockPos> waypoints = CablePathPlanner.waypoints(a.position(), b.position());
        long operationCount = CablePathPlanner.operationCount(a.position(), b.position());
        if (operationCount > maxDetailedVoxels()) {
            for (int index = 1; index < waypoints.size(); index++) {
                addRegionPreview(boxes, List.of(waypoints.get(index - 1), waypoints.get(index)),
                        0.15F, 0.60F, 0.75F, 0.85F);
            }
            return;
        }

        GeometryPlan plan = CablePathPlanner.plan(a, b, maxDetailedVoxels());
        List<PreviewTarget> targets = plan.operations().stream()
                .map(operation -> new PreviewTarget(operation.location().position(), state.cableMaterial()))
                .toList();
        for (int index = 1; index < waypoints.size(); index++) {
            BlockPos start = waypoints.get(index - 1);
            BlockPos end = waypoints.get(index);
            List<PreviewTarget> segmentTargets = targets.stream()
                    .filter(target -> isBetween(start, end, target.position()))
                    .toList();
            addTrackedRegionPreview(boxes, List.of(start, end), segmentTargets, 0.15F, 0.60F, 0.75F);
        }
        addOperationPreview(boxes, targets, 0.25F, 0.95F, 0.85F);
    }

    private static boolean isBetween(BlockPos start, BlockPos end, BlockPos position) {
        return position.getX() >= Math.min(start.getX(), end.getX()) &&
                position.getX() <= Math.max(start.getX(), end.getX()) &&
                position.getY() >= Math.min(start.getY(), end.getY()) &&
                position.getY() <= Math.max(start.getY(), end.getY()) &&
                position.getZ() >= Math.min(start.getZ(), end.getZ()) &&
                position.getZ() <= Math.max(start.getZ(), end.getZ());
    }

    private static PreviewTarget previewTarget(BoundGeometryOperation operation) {
        return new PreviewTarget(operation.operation().location().position(), operation.block());
    }

    private static void addCopyPreview(List<PreviewBox> boxes, World world, ManipulatorState state, int dimension) {
        ManipulatorLocation a = state.selectionA();
        ManipulatorLocation b = state.selectionB();
        ManipulatorLocation c = state.selectionC();
        if (!sameDimension(dimension, a, b)) return;

        boolean moving = state.placeMode() == ManipulatorPlaceMode.MOVING;
        if (!moving || c == null || c.dimension() != dimension) {
            addRegionPreview(boxes, a, b, dimension, 0.20F, 0.75F, 1.00F);
        }
        if (c == null || c.dimension() != dimension) return;
        CopyTransform transform = moving ? CopyTransform.identity() : new CopyTransform(state.copyTransform(),
                state.copyRepeatX(), state.copyRepeatY(), state.copyRepeatZ());
        CopyPlan plan = CopyPlanner.plan(a, b, c, transform, maxDetailedVoxels() + 1);
        List<BlockPos> targets = plan.operations().stream().map(operation -> operation.target()).toList();
        List<PreviewTarget> previewTargets = plan.operations().stream()
                .map(operation -> copyPreviewTarget(world, state, operation, moving))
                .filter(target -> target != null)
                .toList();
        if (moving) {
            List<BlockPos> sources = plan.operations().stream().map(operation -> operation.source()).toList();
            List<PreviewTarget> clearedSources = sources.stream()
                    .map(source -> new PreviewTarget(source, BlockSpec.air()))
                    .toList();
            addAdaptiveRegionPreview(boxes, sources, clearedSources, 0.20F, 0.75F, 1.00F);
            addOperationPreview(boxes, clearedSources, 0.20F, 0.75F, 1.00F);
        }
        addAdaptiveRegionPreview(boxes, targets, previewTargets, 0.75F, 0.50F, 0.15F);
        addOperationPreview(boxes, previewTargets, 0.95F, 0.35F, 0.20F);
    }

    private static PreviewTarget copyPreviewTarget(World world, ManipulatorState state,
                                                   CopyPositionOperation operation, boolean moving) {
        if (!world.isBlockLoaded(operation.source(), false)) {
            return new PreviewTarget(operation.target(), null);
        }

        IBlockState sourceState = world.getBlockState(operation.source());
        // Moving an empty source is a server-side no-op. This also matters after a completed move: rebuilding the
        // client preview must not reinterpret the now-empty source as a request to clear the populated target.
        if (moving && sourceState.getBlock() == Blocks.AIR) return null;
        if (moving && world.getTileEntity(operation.source()) != null) {
            return new PreviewTarget(operation.target(), null);
        }

        BlockSpec source = BlockSpec.fromState(sourceState);
        BlockSpec expected = moving ? source : source.transformed(state.copyTransform());
        return new PreviewTarget(operation.target(), expected);
    }

    private static void addOperationPreview(List<PreviewBox> boxes, List<PreviewTarget> targets, float red, float green,
                                            float blue) {
        if (targets.size() > maxDetailedVoxels()) return;
        for (PreviewTarget target : targets) {
            boxes.add(PreviewBox.operation(target, red, green, blue));
        }
    }

    private static void addSelection(List<PreviewBox> boxes, ManipulatorLocation location, int dimension, float red,
                                     float green, float blue) {
        if (location == null || location.dimension() != dimension) return;
        boxes.add(new PreviewBox(new AxisAlignedBB(location.position()), red, green, blue, 0.24F));
    }

    private static void addRegionPreview(List<PreviewBox> boxes, ManipulatorLocation a, ManipulatorLocation b,
                                         int dimension, float red, float green, float blue) {
        if (!sameDimension(dimension, a, b)) return;
        addRegionPreview(boxes, List.of(a.position(), b.position()), red, green, blue, 0.85F);
    }

    private static void addRegionPreview(List<PreviewBox> boxes, List<BlockPos> positions, float red, float green,
                                         float blue, float alpha) {
        if (positions.isEmpty()) return;
        boxes.add(new PreviewBox(bounds(positions), red, green, blue, 0.25F));
    }

    private static void addTrackedRegionPreview(List<PreviewBox> boxes, List<BlockPos> positions,
                                                List<PreviewTarget> targets, float red, float green, float blue) {
        if (positions.isEmpty()) return;
        boxes.add(PreviewBox.trackedRegion(bounds(positions), targets, red, green, blue));
    }

    private static void addAdaptiveRegionPreview(List<PreviewBox> boxes, List<BlockPos> positions,
                                                 List<PreviewTarget> targets, float red, float green, float blue) {
        if (targets.size() > maxDetailedVoxels()) {
            addTrackedRegionPreview(boxes, positions, targets, red, green, blue);
            return;
        }
        for (PreviewTarget target : targets) {
            boxes.add(PreviewBox.faintOperation(target, red, green, blue));
        }
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

    private static AxisAlignedBB hintBounds(BlockPos position) {
        return new AxisAlignedBB(position).grow(-0.25D);
    }

    private static int maxDetailedVoxels() {
        return Math.max(1, MatterManipulatorConfig.maxHints);
    }

    private record Preview(List<PreviewBox> boxes, List<PreviewBox> transientBoxes,
                           long transientExpiresAtMillis) {

        private List<PreviewBox> visibleBoxes(long now) {
            if (transientBoxes.isEmpty() || transientExpiresAtMillis == 0L ||
                    now < transientExpiresAtMillis) {
                if (transientBoxes.isEmpty()) return boxes;
                List<PreviewBox> visible = new ArrayList<>(boxes.size() + transientBoxes.size());
                visible.addAll(boxes);
                visible.addAll(transientBoxes);
                return visible;
            }
            return boxes;
        }

        private static Preview empty() {
            return new Preview(List.of(), List.of(), 0L);
        }
    }

    private record PreviewTarget(BlockPos position, BlockSpec expected) {}

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

    private record PreviewBox(AxisAlignedBB bounds, float red, float green, float blue, float alpha,
                              PreviewTarget target, List<PreviewTarget> completionGroup) {

        private PreviewBox(AxisAlignedBB bounds, float red, float green, float blue, float alpha) {
            this(bounds, red, green, blue, alpha, null, null);
        }

        private static PreviewBox operation(PreviewTarget target, float red, float green, float blue) {
            return new PreviewBox(hintBounds(target.position()), red, green, blue, 0.60F, target, null);
        }

        private static PreviewBox faintOperation(PreviewTarget target, float red, float green, float blue) {
            return new PreviewBox(new AxisAlignedBB(target.position()), red, green, blue, 0.18F, target, null);
        }

        private static PreviewBox trackedRegion(AxisAlignedBB bounds, List<PreviewTarget> targets, float red,
                                                float green, float blue) {
            return new PreviewBox(bounds, red, green, blue, 0.25F, null, targets);
        }

        private PreviewBox withColor(float red, float green, float blue) {
            return new PreviewBox(bounds, red, green, blue, alpha, target, completionGroup);
        }

        private boolean isSatisfiedBy(World world) {
            if (target != null) return isTargetSatisfied(world, target);
            if (completionGroup == null || completionGroup.isEmpty()) return false;
            for (PreviewTarget groupedTarget : completionGroup) {
                if (!isTargetSatisfied(world, groupedTarget)) return false;
            }
            return true;
        }

        private static boolean isTargetSatisfied(World world, PreviewTarget target) {
            return target.expected() != null && world.isBlockLoaded(target.position(), false) &&
                    target.expected().matchesWorldState(world.getBlockState(target.position()));
        }
    }
}
