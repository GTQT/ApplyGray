package applygray.integration.jei;

/**
 * Marker for pattern encoding terminals that can rename duplicated HEI/JEI inputs.
 */
public interface AutoPatternItemNaming {

    boolean applygray$autoNamePatternItems();

    void applygray$setAutoNamePatternItems(boolean autoName);
}
