package applygray.integration.ae2;

/** Decides whether an AE2 crafting provider needs its native pattern list remounted after a state transition. */
public final class CraftingProviderRefreshPolicy {

    private CraftingProviderRefreshPolicy() {}

    /** Deactivation always removes published patterns; activation is needed only for providers that publish them. */
    public static boolean needsNativeRefresh(boolean active, boolean refreshAfterActivation) {
        return !active || refreshAfterActivation;
    }

    /** Allows a provider to distinguish a real offline transition from a grid-boot topology transition. */
    public static boolean needsNativeRefresh(boolean active, boolean refreshAfterActivation,
                                             boolean refreshAfterDeactivation) {
        return active ? refreshAfterActivation : refreshAfterDeactivation;
    }
}
