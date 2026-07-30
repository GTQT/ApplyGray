package applygray.integration.ae2.rules;

import applygray.ApplyGrayMod;
import applygray.integration.ae2.DynamicRecipePatternRegistry;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import gregtech.api.metatileentity.multiblock.MultiblockControllerBase;

import ae2.api.stacks.AEKey;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/** Atomic rule-set lifecycle, including conservative fallback on a failed hot reload. */
public final class RecipePatternRules {

    private static final String DEFAULTS_RESOURCE = "/assets/applygray/recipe-pattern-rules/defaults.json";
    private static final Gson RULE_FILE_GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final AtomicReference<RuleSet> ACTIVE = new AtomicReference<>(RuleSet.empty());
    private static final List<RecipePatternFactContributor> FACT_CONTRIBUTORS = new CopyOnWriteArrayList<>();
    private static final List<RecipePatternConstraint> CONSTRAINTS = new CopyOnWriteArrayList<>();
    private static final boolean UNSAFE_MODE = Boolean.getBoolean("applygray.recipePatternRules.unsafe");

    private static volatile Path rulesDirectory;
    private static volatile String lastObservedState;

    private RecipePatternRules() {
    }

    public static void initialize(java.io.File modConfigurationDirectory) {
        if (modConfigurationDirectory == null) {
            ApplyGrayMod.LOGGER.warn("Recipe-pattern rules were not initialized because the config directory is absent");
            return;
        }
        rulesDirectory = modConfigurationDirectory.toPath().resolve("applygray").resolve("recipe-pattern-rules");
        try {
            Files.createDirectories(rulesDirectory);
            Path defaults = rulesDirectory.resolve("defaults.json");
            if (!Files.exists(defaults)) {
                try (InputStream input = RecipePatternRules.class.getResourceAsStream(DEFAULTS_RESOURCE)) {
                    if (input == null) throw new IOException("Missing bundled rule resource " + DEFAULTS_RESOURCE);
                    Files.copy(input, defaults, StandardCopyOption.REPLACE_EXISTING);
                }
                ApplyGrayMod.LOGGER.info("Created default RecipeMap pattern rule package at {}", defaults);
            }
            migrateDefaultRules(defaults);
            reload();
        } catch (IOException exception) {
            ApplyGrayMod.LOGGER.error("Failed to initialize RecipeMap pattern rules at {}. Keeping built-in fallback.",
                    rulesDirectory, exception);
        }

        for (RecipePatternFactContributor contributor : java.util.ServiceLoader.load(RecipePatternFactContributor.class)) {
            registerFactContributor(contributor);
        }
        for (RecipePatternConstraint constraint : java.util.ServiceLoader.load(RecipePatternConstraint.class)) {
            registerConstraint(constraint);
        }
        if (UNSAFE_MODE) {
            ApplyGrayMod.LOGGER.warn("Unsafe expected-value recipe-pattern output mode is enabled");
        }
    }

    public static RuleSet getActive() {
        return ACTIVE.get();
    }

    public static RuleDecision evaluate(RuleContext context) {
        return ACTIVE.get().evaluate(context, CONSTRAINTS);
    }

    public static void reloadIfChanged() {
        Path directory = rulesDirectory;
        if (directory == null) return;
        try {
            String currentState = getDirectoryState(directory);
            if (!currentState.equals(lastObservedState)) reload();
        } catch (IOException exception) {
            ApplyGrayMod.LOGGER.warn("Could not check RecipeMap pattern rule changes at {}", directory, exception);
        }
    }

    public static synchronized boolean reload() {
        Path directory = rulesDirectory;
        if (directory == null) return false;
        try {
            RuleSet loaded = RuleSetLoader.load(directory);
            RuleSet previous = ACTIVE.getAndSet(loaded);
            lastObservedState = getDirectoryState(directory);
            DynamicRecipePatternRegistry.invalidateRuleSetContents(previous.getVersion(), loaded.getVersion());
            ApplyGrayMod.LOGGER.info("Loaded {} RecipeMap pattern rules (version {}) from {}; planning budget: {}",
                    loaded.getRuleCount(), loaded.getVersion().substring(0, Math.min(12, loaded.getVersion().length())),
                    directory, loaded.getPlanningBudget().summarize());
            return true;
        } catch (IOException exception) {
            ApplyGrayMod.LOGGER.error("RecipeMap pattern rule reload failed at {}; keeping rule set {}", directory,
                    ACTIVE.get().getVersion(), exception);
            return false;
        }
    }

    public static void registerFactContributor(RecipePatternFactContributor contributor) {
        if (contributor != null && !FACT_CONTRIBUTORS.contains(contributor)) {
            FACT_CONTRIBUTORS.add(contributor);
            ApplyGrayMod.LOGGER.info("Registered RecipeMap pattern fact contributor {}", contributor.getClass().getName());
            DynamicRecipePatternRegistry.invalidateRuleEngineContents(
                    "fact contributor " + contributor.getClass().getName());
        }
    }

    public static void registerConstraint(RecipePatternConstraint constraint) {
        if (constraint != null && !CONSTRAINTS.contains(constraint)) {
            CONSTRAINTS.add(constraint);
            ApplyGrayMod.LOGGER.info("Registered RecipeMap pattern constraint {}", constraint.getClass().getName());
            DynamicRecipePatternRegistry.invalidateRuleEngineContents(
                    "constraint " + constraint.getClass().getName());
        }
    }

    public static List<RecipePatternFactContributor> getFactContributors() {
        return new ArrayList<>(FACT_CONTRIBUTORS);
    }

    /** Queries trusted adapters for a physical lower bound without allowing an adapter failure to weaken safety. */
    public static long getGuaranteedOutputLowerBound(RuleContext context, AEKey target) {
        if (context == null || target == null) return 0;
        long lowerBound = 0;
        for (RecipePatternConstraint constraint : CONSTRAINTS) {
            try {
                long candidate = constraint.getGuaranteedOutputLowerBound(context, target);
                if (candidate < 0) {
                    ApplyGrayMod.LOGGER.warn("Recipe-pattern constraint {} returned a negative guaranteed lower bound " +
                                    "for {}; ignoring it",
                            constraint.getClass().getName(), target);
                    continue;
                }
                lowerBound = Math.max(lowerBound, candidate);
            } catch (RuntimeException exception) {
                ApplyGrayMod.LOGGER.warn("Recipe-pattern constraint {} failed while proving chanced target {}",
                        constraint.getClass().getName(), target, exception);
            }
        }
        return lowerBound;
    }

    /** Captures only serializable/simple adapter facts while the provider is on the server thread. */
    public static Map<String, Object> collectMachineFacts(MultiblockControllerBase controller) {
        Map<String, Object> facts = new LinkedHashMap<>();
        for (RecipePatternFactContributor contributor : FACT_CONTRIBUTORS) {
            try {
                contributor.contributeMachineFacts(controller, facts);
            } catch (RuntimeException exception) {
                ApplyGrayMod.LOGGER.warn("Recipe-pattern machine fact contributor {} failed",
                        contributor.getClass().getName(), exception);
            }
        }
        return facts;
    }

    public static boolean isUnsafeModeEnabled() {
        return UNSAFE_MODE;
    }

    /** Adds missing bundled defaults without replacing existing rule definitions or custom budget values. */
    private static void migrateDefaultRules(Path defaults) throws IOException {
        JsonObject document = readRuleDocument(defaults, defaults.toString());
        JsonObject bundled = readBundledDefaultRules();
        JsonArray rules = getRulesArray(document, defaults.toString());
        JsonArray bundledRules = getRulesArray(bundled, "bundled defaults");

        List<String> addedRuleIds = new ArrayList<>();
        for (JsonElement bundledRule : bundledRules) {
            if (!bundledRule.isJsonObject()) continue;
            JsonObject bundledRuleObject = bundledRule.getAsJsonObject();
            if (!bundledRuleObject.has("id") || !bundledRuleObject.get("id").isJsonPrimitive()) continue;
            String ruleId = bundledRuleObject.get("id").getAsString();
            if (containsRuleId(rules, ruleId)) continue;
            rules.add(bundledRule);
            addedRuleIds.add(ruleId);
        }

        JsonObject bundledBudget = getPlanningBudgetObject(bundled, "bundled defaults");
        JsonElement bundledDynamicCandidateLimit = bundledBudget.get("maxDynamicCandidatesForCost");
        if (bundledDynamicCandidateLimit == null) {
            throw new IOException("Bundled defaults have no maxDynamicCandidatesForCost");
        }
        JsonObject budget = getOrCreatePlanningBudget(document, defaults.toString());
        JsonElement currentDynamicCandidateLimit = budget.get("maxDynamicCandidatesForCost");
        boolean upgradedLegacyDynamicCandidateLimit = currentDynamicCandidateLimit == null ||
                isLegacyDynamicCandidateLimit(currentDynamicCandidateLimit);
        if (upgradedLegacyDynamicCandidateLimit) {
            budget.add("maxDynamicCandidatesForCost", bundledDynamicCandidateLimit);
        }

        if (addedRuleIds.isEmpty() && !upgradedLegacyDynamicCandidateLimit) return;
        try (Writer writer = Files.newBufferedWriter(defaults, StandardCharsets.UTF_8,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            RULE_FILE_GSON.toJson(document, writer);
            writer.write(System.lineSeparator());
        }
        ApplyGrayMod.LOGGER.info("Migrated RecipeMap pattern defaults at {}: addedRules={}, " +
                        "maxDynamicCandidatesForCost={}.",
                defaults, addedRuleIds, bundledDynamicCandidateLimit.getAsInt());
    }

    private static JsonObject readBundledDefaultRules() throws IOException {
        try (InputStream input = RecipePatternRules.class.getResourceAsStream(DEFAULTS_RESOURCE)) {
            if (input == null) throw new IOException("Missing bundled rule resource " + DEFAULTS_RESOURCE);
            try (Reader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
                JsonElement root = JsonParser.parseReader(reader);
                if (root == null || !root.isJsonObject()) {
                    throw new IOException("Bundled default rules are not a JSON object");
                }
                return root.getAsJsonObject();
            }
        } catch (RuntimeException exception) {
            throw new IOException("Could not parse bundled RecipeMap pattern defaults", exception);
        }
    }

    private static JsonObject readRuleDocument(Path source, String description) throws IOException {
        try (Reader reader = Files.newBufferedReader(source, StandardCharsets.UTF_8)) {
            JsonElement root = JsonParser.parseReader(reader);
            if (root == null || !root.isJsonObject()) {
                throw new IOException("Default rule file is not a JSON object: " + description);
            }
            return root.getAsJsonObject();
        } catch (RuntimeException exception) {
            throw new IOException("Could not parse default rule file " + description, exception);
        }
    }

    private static JsonArray getRulesArray(JsonObject document, String description) throws IOException {
        JsonElement rulesElement = document.get("rules");
        if (rulesElement == null || !rulesElement.isJsonArray()) {
            throw new IOException("Default rule file has no rules array: " + description);
        }
        return rulesElement.getAsJsonArray();
    }

    private static JsonObject getPlanningBudgetObject(JsonObject document, String description) throws IOException {
        JsonElement planningBudget = document.get("planningBudget");
        if (planningBudget == null || !planningBudget.isJsonObject()) {
            throw new IOException("Default rule file has no planningBudget object: " + description);
        }
        return planningBudget.getAsJsonObject();
    }

    private static JsonObject getOrCreatePlanningBudget(JsonObject document, String description) throws IOException {
        JsonElement planningBudget = document.get("planningBudget");
        if (planningBudget == null) {
            JsonObject created = new JsonObject();
            document.add("planningBudget", created);
            return created;
        }
        if (!planningBudget.isJsonObject()) {
            throw new IOException("Default rule file has an invalid planningBudget: " + description);
        }
        return planningBudget.getAsJsonObject();
    }

    private static boolean containsRuleId(JsonArray rules, String ruleId) {
        for (JsonElement ruleElement : rules) {
            if (!ruleElement.isJsonObject()) continue;
            JsonElement existingId = ruleElement.getAsJsonObject().get("id");
            if (existingId != null && existingId.isJsonPrimitive() && ruleId.equals(existingId.getAsString())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isLegacyDynamicCandidateLimit(JsonElement value) {
        return value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber() &&
                "2".equals(value.getAsString());
    }

    private static String getDirectoryState(Path directory) throws IOException {
        StringBuilder state = new StringBuilder();
        try (Stream<Path> paths = Files.walk(directory)) {
            paths.filter(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".json"))
                    .sorted().forEach(path -> {
                        try {
                            FileTime modified = Files.getLastModifiedTime(path);
                            state.append(directory.relativize(path)).append(':').append(modified.toMillis()).append(':')
                                    .append(Files.size(path)).append('\n');
                        } catch (IOException exception) {
                            throw new RuleStateException(exception);
                        }
                    });
        } catch (RuleStateException exception) {
            throw exception.getCause();
        }
        return state.toString();
    }

    private static final class RuleStateException extends RuntimeException {

        private RuleStateException(IOException cause) {
            super(cause);
        }

        @Override
        public IOException getCause() {
            return (IOException) super.getCause();
        }
    }
}
