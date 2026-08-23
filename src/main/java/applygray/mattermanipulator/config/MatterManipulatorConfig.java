package applygray.mattermanipulator.config;

import java.io.File;

import net.minecraftforge.common.config.Configuration;

/** Global Matter Manipulator settings shared by the client preview and server batch executor. */
public final class MatterManipulatorConfig {

    public static int maxHints = 1_000_000;
    public static int statusExpirationSeconds = 60;
    public static boolean hintsOnTop = true;
    public static boolean autoClearPaste = true;
    public static boolean clearTransformWithSelections = true;
    public static boolean meEmptying = true;
    public static int mk3BlocksPerPlace = 256;
    public static boolean debugLogging = false;

    private MatterManipulatorConfig() {}

    public static void load(File configurationDirectory) {
        File file = new File(new File(configurationDirectory, "applygray"), "matter-manipulator.cfg");
        Configuration configuration = new Configuration(file);
        configuration.load();

        maxHints = configuration.getInt("maxHints", "rendering", maxHints, 1, 4_000_000,
                "Maximum number of detailed preview hints rendered on the client");
        statusExpirationSeconds = configuration.getInt("statusExpirationSeconds", "rendering",
                statusExpirationSeconds, 0, 86_400, "Build warning/error hint lifetime in seconds; 0 never expires");
        hintsOnTop = configuration.getBoolean("hintsOnTop", "rendering", hintsOnTop,
                "Draw preview hints over terrain");
        autoClearPaste = configuration.getBoolean("autoClearPaste", "interaction", autoClearPaste,
                "Clear paste point when starting a new copy or move selection");
        clearTransformWithSelections = configuration.getBoolean("clearTransformWithSelections", "interaction",
                clearTransformWithSelections, "Clear copy transform and repeats with selections");
        meEmptying = configuration.getBoolean("meEmptying", "building", meEmptying,
                "Allow supported ME output containers to be emptied during removal");
        mk3BlocksPerPlace = configuration.getInt("mk3BlocksPerPlace", "building", mk3BlocksPerPlace, 1, 4096,
                "MK3 maximum blocks per server batch");
        debugLogging = configuration.getBoolean("debugLogging", "debug", debugLogging,
                "Enable low-frequency Matter Manipulator diagnostics");

        if (configuration.hasChanged()) configuration.save();
    }
}
