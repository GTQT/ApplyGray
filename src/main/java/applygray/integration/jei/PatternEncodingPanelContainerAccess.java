package applygray.integration.jei;

import ae2.container.me.patternencode.ContainerPatternEncodingTerm;

/**
 * Lets the encoding panels reach the container they were created for.
 */
public interface PatternEncodingPanelContainerAccess {

    ContainerPatternEncodingTerm applygray$getContainer();
}
