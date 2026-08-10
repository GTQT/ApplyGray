package applygray.mattermanipulator.state;

import net.minecraft.util.Rotation;

/** Orthogonal copy rotation around the vertical axis. */
public enum ManipulatorRotation {

    NONE(Rotation.NONE),
    CLOCKWISE_90(Rotation.CLOCKWISE_90),
    CLOCKWISE_180(Rotation.CLOCKWISE_180),
    COUNTERCLOCKWISE_90(Rotation.COUNTERCLOCKWISE_90);

    private final Rotation minecraftRotation;

    ManipulatorRotation(Rotation minecraftRotation) {
        this.minecraftRotation = minecraftRotation;
    }

    public Rotation minecraftRotation() {
        return minecraftRotation;
    }
}
