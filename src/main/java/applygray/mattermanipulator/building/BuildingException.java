package applygray.mattermanipulator.building;

import net.minecraft.util.math.BlockPos;

/** A player-actionable reason that a server-side building operation was rejected. */
public final class BuildingException extends RuntimeException {

    private final Reason reason;
    private final BlockPos position;

    public BuildingException(Reason reason, BlockPos position, String message) {
        super(message);
        this.reason = reason;
        this.position = position;
    }

    public Reason reason() {
        return reason;
    }

    public BlockPos position() {
        return position;
    }

    public enum Reason {
        CHUNK_NOT_LOADED,
        OUTSIDE_WORLD_BORDER,
        PERMISSION_DENIED,
        UNSUPPORTED_BLOCK,
        UNSUPPORTED_TILE_ENTITY,
        REMOVAL_NOT_SUPPORTED,
        REMOVAL_NOT_ALLOWED,
        CANNOT_PLACE,
        UNBREAKABLE,
        OVERLAPPING_MOVE,
        BLOCK_CHANGE_FAILED
    }
}
