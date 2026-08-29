package applygray.mattermanipulator.integration.ae2;

/** Mixin bridge used only to reject an AE2 Caner while it owns an in-flight crafting result. */
public interface PortableCanerRuntimeStateAccess {

    boolean applygray$hasInFlightCanerState();
}
