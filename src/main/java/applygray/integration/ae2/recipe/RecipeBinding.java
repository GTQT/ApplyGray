package applygray.integration.ae2.recipe;

import net.minecraft.nbt.NBTTagCompound;

import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * Immutable contract between a generated AE pattern and the exact GregTech recipe it may execute.
 *
 * <p>The fingerprint identifies one recipe in one version of a RecipeMap. The target is part of the contract even
 * though it is not used by GregTech execution, because one physical recipe can safely materialize several distinct
 * single-output AE patterns.</p>
 */
public final class RecipeBinding {

    // Version 3 assigns recipe ordinals after a canonical content sort, so bindings survive recipe-map reload order.
    public static final int FINGERPRINT_VERSION = 3;
    public static final int NORMALIZATION_VERSION = 1;
    private static final int SERIALIZATION_VERSION = 2;

    private final String recipeMapId;
    private final int recipeFingerprintVersion;
    private final String recipeFingerprint;
    private final String recipeMapContentVersion;
    private final String targetKey;
    private final int normalizationVersion;
    private final String ruleSetVersion;
    private final String machineProfileVersion;

    public RecipeBinding(String recipeMapId, int recipeFingerprintVersion, String recipeFingerprint,
                         String recipeMapContentVersion, String targetKey, int normalizationVersion,
                         String ruleSetVersion, String machineProfileVersion) {
        this.recipeMapId = requireText(recipeMapId, "recipeMapId");
        this.recipeFingerprintVersion = recipeFingerprintVersion;
        this.recipeFingerprint = requireText(recipeFingerprint, "recipeFingerprint");
        this.recipeMapContentVersion = requireText(recipeMapContentVersion, "recipeMapContentVersion");
        this.targetKey = requireText(targetKey, "targetKey");
        this.normalizationVersion = normalizationVersion;
        this.ruleSetVersion = requireText(ruleSetVersion, "ruleSetVersion");
        this.machineProfileVersion = requireText(machineProfileVersion, "machineProfileVersion");
    }

    public String getRecipeMapId() {
        return recipeMapId;
    }

    public int getRecipeFingerprintVersion() {
        return recipeFingerprintVersion;
    }

    public String getRecipeFingerprint() {
        return recipeFingerprint;
    }

    public String getRecipeMapContentVersion() {
        return recipeMapContentVersion;
    }

    public String getTargetKey() {
        return targetKey;
    }

    public int getNormalizationVersion() {
        return normalizationVersion;
    }

    public String getRuleSetVersion() {
        return ruleSetVersion;
    }

    public String getMachineProfileVersion() {
        return machineProfileVersion;
    }

    public boolean isForRecipeMap(String recipeMapName) {
        return recipeMapId.equals(recipeMapName);
    }

    public NBTTagCompound writeToNBT() {
        NBTTagCompound data = new NBTTagCompound();
        data.setInteger("Version", SERIALIZATION_VERSION);
        data.setString("RecipeMap", recipeMapId);
        data.setInteger("FingerprintVersion", recipeFingerprintVersion);
        data.setString("Fingerprint", recipeFingerprint);
        data.setString("MapContentVersion", recipeMapContentVersion);
        data.setString("Target", targetKey);
        data.setInteger("NormalizationVersion", normalizationVersion);
        data.setString("RuleSetVersion", ruleSetVersion);
        data.setString("MachineProfileVersion", machineProfileVersion);
        return data;
    }

    @Nullable
    public static RecipeBinding readFromNBT(NBTTagCompound data) {
        if (data == null || data.getInteger("Version") != SERIALIZATION_VERSION ||
                !data.hasKey("RecipeMap", 8) || !data.hasKey("Fingerprint", 8) ||
                !data.hasKey("MapContentVersion", 8) || !data.hasKey("Target", 8) ||
                !data.hasKey("RuleSetVersion", 8) || !data.hasKey("MachineProfileVersion", 8)) {
            return null;
        }
        try {
            return new RecipeBinding(data.getString("RecipeMap"), data.getInteger("FingerprintVersion"),
                    data.getString("Fingerprint"), data.getString("MapContentVersion"),
                    data.getString("Target"), data.getInteger("NormalizationVersion"),
                    data.getString("RuleSetVersion"), data.getString("MachineProfileVersion"));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    public String describe() {
        return recipeMapId + '#' + recipeFingerprint.substring(0, Math.min(12, recipeFingerprint.length()));
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof RecipeBinding binding)) return false;
        return recipeFingerprintVersion == binding.recipeFingerprintVersion &&
                normalizationVersion == binding.normalizationVersion &&
                recipeMapId.equals(binding.recipeMapId) && recipeFingerprint.equals(binding.recipeFingerprint) &&
                recipeMapContentVersion.equals(binding.recipeMapContentVersion) && targetKey.equals(binding.targetKey) &&
                ruleSetVersion.equals(binding.ruleSetVersion) &&
                machineProfileVersion.equals(binding.machineProfileVersion);
    }

    @Override
    public int hashCode() {
        return Objects.hash(recipeMapId, recipeFingerprintVersion, recipeFingerprint, recipeMapContentVersion,
                targetKey, normalizationVersion, ruleSetVersion, machineProfileVersion);
    }

    @Override
    public String toString() {
        return "RecipeBinding{" + describe() + ", target=" + targetKey + ", rules=" + ruleSetVersion + '}';
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
        return value;
    }
}
