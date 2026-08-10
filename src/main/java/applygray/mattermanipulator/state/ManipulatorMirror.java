package applygray.mattermanipulator.state;

import net.minecraft.util.Mirror;

/** Orthogonal copy mirror applied before rotation. */
public enum ManipulatorMirror {

    NONE(Mirror.NONE),
    LEFT_RIGHT(Mirror.LEFT_RIGHT),
    FRONT_BACK(Mirror.FRONT_BACK);

    private final Mirror minecraftMirror;

    ManipulatorMirror(Mirror minecraftMirror) {
        this.minecraftMirror = minecraftMirror;
    }

    public Mirror minecraftMirror() {
        return minecraftMirror;
    }
}
