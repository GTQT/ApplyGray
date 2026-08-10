package applygray.mattermanipulator.planning;

import java.util.Objects;

import applygray.mattermanipulator.state.ManipulatorLocation;

/** Immutable operation descriptor; a later transaction binds it to a concrete block specification. */
public record ManipulatorOperation(Type type, ManipulatorLocation location, VoxelRole role, int renderOrder,
                                   int buildOrder) {

    public ManipulatorOperation {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(role, "role");
    }

    public enum Type {
        PLACE,
        REMOVE,
        CONFIGURE
    }
}
