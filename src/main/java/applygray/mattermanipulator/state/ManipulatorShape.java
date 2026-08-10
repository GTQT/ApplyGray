package applygray.mattermanipulator.state;

public enum ManipulatorShape {

    LINE(false),
    CUBE(false),
    SPHERE(false),
    CYLINDER(true);

    private final boolean requiresThirdPoint;

    ManipulatorShape(boolean requiresThirdPoint) {
        this.requiresThirdPoint = requiresThirdPoint;
    }

    public boolean requiresThirdPoint() {
        return requiresThirdPoint;
    }
}
