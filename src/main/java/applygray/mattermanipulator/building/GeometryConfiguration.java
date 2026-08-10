package applygray.mattermanipulator.building;

import java.util.Objects;
import java.util.Random;

import applygray.mattermanipulator.planning.VoxelRole;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.common.util.Constants;

/** Persistent material slots for geometry operations. */
public final class GeometryConfiguration {

    private static final String KEY_CORNERS = "Corners";
    private static final String KEY_EDGES = "Edges";
    private static final String KEY_FACES = "Faces";
    private static final String KEY_VOLUMES = "Volumes";

    private final WeightedBlockList corners;
    private final WeightedBlockList edges;
    private final WeightedBlockList faces;
    private final WeightedBlockList volumes;

    public GeometryConfiguration() {
        this(new WeightedBlockList(BlockSpec.air()), new WeightedBlockList(BlockSpec.air()),
                new WeightedBlockList(BlockSpec.air()), new WeightedBlockList(BlockSpec.air()));
    }

    private GeometryConfiguration(WeightedBlockList corners, WeightedBlockList edges, WeightedBlockList faces,
                                  WeightedBlockList volumes) {
        this.corners = Objects.requireNonNull(corners, "corners");
        this.edges = Objects.requireNonNull(edges, "edges");
        this.faces = Objects.requireNonNull(faces, "faces");
        this.volumes = Objects.requireNonNull(volumes, "volumes");
    }

    public WeightedBlockList corners() {
        return corners;
    }

    public WeightedBlockList edges() {
        return edges;
    }

    public WeightedBlockList faces() {
        return faces;
    }

    public WeightedBlockList volumes() {
        return volumes;
    }

    public BlockSpec select(VoxelRole role, Random random) {
        return switch (role) {
            case CORNER -> corners.select(random);
            case EDGE -> edges.select(random);
            case FACE -> faces.select(random);
            case VOLUME -> volumes.select(random);
        };
    }

    /** Sets one geometry role to a single material; the full weighted editor adds alternatives later. */
    public void setSingle(VoxelRole role, BlockSpec specification) {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(specification, "specification");
        switch (role) {
            case CORNER -> corners.setSingle(specification);
            case EDGE -> edges.setSingle(specification);
            case FACE -> faces.setSingle(specification);
            case VOLUME -> volumes.setSingle(specification);
        }
    }

    public NBTTagCompound writeToNbt() {
        NBTTagCompound data = new NBTTagCompound();
        data.setTag(KEY_CORNERS, corners.writeToNbt());
        data.setTag(KEY_EDGES, edges.writeToNbt());
        data.setTag(KEY_FACES, faces.writeToNbt());
        data.setTag(KEY_VOLUMES, volumes.writeToNbt());
        return data;
    }

    public static GeometryConfiguration readFromNbt(NBTTagCompound data) {
        if (data == null) return new GeometryConfiguration();
        return new GeometryConfiguration(readList(data, KEY_CORNERS), readList(data, KEY_EDGES),
                readList(data, KEY_FACES), readList(data, KEY_VOLUMES));
    }

    private static WeightedBlockList readList(NBTTagCompound data, String key) {
        return data.hasKey(key, Constants.NBT.TAG_COMPOUND)
                ? WeightedBlockList.readFromNbt(data.getCompoundTag(key), BlockSpec.air())
                : new WeightedBlockList(BlockSpec.air());
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof GeometryConfiguration configuration)) return false;
        return corners.equals(configuration.corners) && edges.equals(configuration.edges) &&
                faces.equals(configuration.faces) && volumes.equals(configuration.volumes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(corners, edges, faces, volumes);
    }
}
