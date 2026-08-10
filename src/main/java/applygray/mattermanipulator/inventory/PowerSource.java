package applygray.mattermanipulator.inventory;

/** A reversible server-side energy source for one Matter Manipulator build batch. */
public interface PowerSource {

    String id();

    long extract(long amount, boolean simulate);

    long insert(long amount, boolean simulate);
}
