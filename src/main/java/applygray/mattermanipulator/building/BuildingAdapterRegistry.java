package applygray.mattermanipulator.building;

import java.util.List;
import java.util.Objects;

import net.minecraft.util.Mirror;
import net.minecraft.util.Rotation;
import net.minecraft.util.math.BlockPos;

/** Ordered target-adapter registry. Specialised GT and AE2 adapters are registered before the vanilla fallback. */
public final class BuildingAdapterRegistry {

    private final List<BuildingAdapter> adapters;

    public BuildingAdapterRegistry(List<? extends BuildingAdapter> adapters) {
        this.adapters = List.copyOf(adapters);
        if (this.adapters.isEmpty()) throw new IllegalArgumentException("at least one building adapter is required");
    }

    public static BuildingAdapterRegistry vanillaOnly() {
        return new BuildingAdapterRegistry(List.of(new VanillaBuildingAdapter()));
    }

    public PreparedBlockChange prepareApply(BuildingContext context, BlockPos position, BlockSpec specification) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(specification, "specification");

        for (BuildingAdapter adapter : adapters) {
            if (adapter.supports(context, position, specification)) {
                return adapter.prepareApply(context, position, specification);
            }
        }
        throw new BuildingException(BuildingException.Reason.UNSUPPORTED_BLOCK, position,
                "No target building adapter supports " + specification.sortKey());
    }

    public CapturedBlock capture(BuildingContext context, BlockPos position) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(position, "position");

        for (BuildingAdapter adapter : adapters) {
            if (adapter.supportsCapture(context, position)) {
                return adapter.capture(context, position).withAdapterId(adapter.id());
            }
        }
        throw new BuildingException(BuildingException.Reason.UNSUPPORTED_BLOCK, position,
                "No target building adapter can capture this block");
    }

    public CapturedBlock transformCapture(CapturedBlock captured, Mirror mirror, Rotation rotation) {
        Objects.requireNonNull(captured, "captured");
        Objects.requireNonNull(mirror, "mirror");
        Objects.requireNonNull(rotation, "rotation");
        return adapterFor(captured).transformCapture(captured, mirror, rotation);
    }

    public PreparedBlockChange prepareApply(BuildingContext context, BlockPos position, CapturedBlock captured) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(captured, "captured");
        return adapterFor(captured).prepareApplyCaptured(context, position, captured);
    }

    public PreparedBlockChange prepareRemove(BuildingContext context, BlockPos position) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(position, "position");

        for (BuildingAdapter adapter : adapters) {
            if (adapter.supportsCapture(context, position)) return adapter.prepareRemove(context, position);
        }
        throw new BuildingException(BuildingException.Reason.UNSUPPORTED_BLOCK, position,
                "No target building adapter can remove this block");
    }

    public PreparedBlockChange prepareMove(BuildingContext context, BlockPos source, BlockPos target) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");

        for (BuildingAdapter adapter : adapters) {
            if (adapter.supportsMove(context, source, target)) return adapter.prepareMove(context, source, target);
        }
        throw new BuildingException(BuildingException.Reason.UNSUPPORTED_BLOCK, source,
                "No target building adapter can move this block");
    }

    private BuildingAdapter adapterFor(CapturedBlock captured) {
        for (BuildingAdapter adapter : adapters) {
            if (adapter.id().equals(captured.adapterId())) return adapter;
        }
        throw new IllegalArgumentException("The captured block references an unavailable adapter: " + captured.adapterId());
    }
}
