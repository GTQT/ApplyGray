package applygray.integration.jei;

/**
 * Marker for pattern encoding terminals that can drop integrated circuits during HEI/JEI imports.
 */
public interface ProcessingPatternCircuitImportMode {

    boolean applygray$ignoreCircuit();

    void applygray$setIgnoreCircuit(boolean ignoreCircuit);
}
