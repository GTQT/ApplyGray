package applygray.mattermanipulator.planning;

import java.util.Objects;

import applygray.mattermanipulator.state.ManipulatorLocation;
import applygray.mattermanipulator.state.ManipulatorShape;

/** Immutable geometry selection assembled from the persistent manipulator state. */
public record GeometrySelection(ManipulatorShape shape, ManipulatorLocation a, ManipulatorLocation b,
                                ManipulatorLocation c) {

    public GeometrySelection {
        Objects.requireNonNull(shape, "shape");
    }

    public boolean isComplete() {
        if (a == null || b == null || a.dimension() != b.dimension()) return false;
        return !shape.requiresThirdPoint() || c != null && a.dimension() == c.dimension();
    }
}
