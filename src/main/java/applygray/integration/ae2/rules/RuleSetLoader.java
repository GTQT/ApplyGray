package applygray.integration.ae2.rules;

import applygray.integration.ae2.recipe.RecipeFingerprint;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/** Strict JSON loader for the compact rule vocabulary used by the dynamic provider. */
final class RuleSetLoader {

    private RuleSetLoader() {
    }

    static RuleSet load(Path directory) throws IOException {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(directory)) {
            paths.filter(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparing(Path::toString)).forEach(files::add);
        }
        if (files.isEmpty()) throw new IOException("No rule JSON files found in " + directory);

        List<CompiledRule> rules = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        PlanningBudget.Builder planningBudget = PlanningBudget.builder();
        StringBuilder versionInput = new StringBuilder();
        for (Path file : files) {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            versionInput.append(directory.relativize(file)).append('\n').append(text).append('\n');
            JsonElement root;
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                root = JsonParser.parseReader(reader);
            } catch (RuntimeException exception) {
                throw new IOException("Invalid JSON in " + file + ": " + exception.getMessage(), exception);
            }
            if (!root.isJsonObject()) throw new IOException("Rule file root must be an object: " + file);
            JsonObject object = root.getAsJsonObject();
            if (!object.has("version") || intValue(object.get("version"), file) != 1) {
                throw new IOException("Rule file must declare supported version 1: " + file);
            }
            if (!object.has("rules") || !object.get("rules").isJsonArray()) {
                throw new IOException("Rule file must contain a rules array: " + file);
            }
            for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                if (!"version".equals(entry.getKey()) && !"rules".equals(entry.getKey()) &&
                        !"planningBudget".equals(entry.getKey())) {
                    throw new IOException("Unknown rule file field " + entry.getKey() + " in " + file);
                }
            }
            if (object.has("planningBudget")) {
                parsePlanningBudget(object.get("planningBudget"), directory.relativize(file).toString(), file,
                        planningBudget);
            }
            for (JsonElement ruleElement : object.getAsJsonArray("rules")) {
                CompiledRule rule = parseRule(ruleElement, file);
                if (!ids.add(rule.getId())) throw new IOException("Duplicate rule id " + rule.getId());
                rules.add(rule);
            }
        }
        return new RuleSet(RecipeFingerprint.sha256(versionInput.toString()), rules, planningBudget.build());
    }

    /** Parses global operational limits with the same priority/stable-name merge semantics as scalar rule effects. */
    private static void parsePlanningBudget(JsonElement element, String source, Path file,
                                            PlanningBudget.Builder target) throws IOException {
        if (!element.isJsonObject()) throw new IOException("planningBudget must be an object in " + file);
        JsonObject budget = element.getAsJsonObject();
        requireOnlyFields(budget, Set.of("priority", "maxRecipesPerTarget", "maxCandidatesPerTarget",
                "maxDynamicCandidatesForCost", "maxRefinedCandidates", "maxNormalPatternsPerTarget",
                "maxInputAlternatives", "maxRouteDepth", "maxRouteExpansionsPerTarget",
                "maxRouteExpansionsPerCalculation", "maxRouteCalculationMillis", "maxSccNodes", "maxSccEdges",
                "maxStandaloneRouteExpansionsPerCalculation", "maxStandaloneRouteCalculationMillis",
                "maxSccAnalysisMillis", "onExhaustion",
                "cycleSafetyOnExhaustion"),
                "planningBudget", file);
        int priority = optionalInt(budget, "priority", 0, file);

        if (budget.has("maxRecipesPerTarget")) {
            target.maxRecipesPerTarget(positiveInt(budget.get("maxRecipesPerTarget"), "maxRecipesPerTarget", file),
                    priority, source);
        }
        if (budget.has("maxCandidatesPerTarget")) {
            target.maxCandidatesPerTarget(positiveInt(budget.get("maxCandidatesPerTarget"),
                    "maxCandidatesPerTarget", file), priority, source);
        }
        if (budget.has("maxDynamicCandidatesForCost")) {
            target.maxDynamicCandidatesForCost(positiveInt(budget.get("maxDynamicCandidatesForCost"),
                    "maxDynamicCandidatesForCost", file), priority, source);
        }
        if (budget.has("maxRefinedCandidates")) {
            target.maxRefinedCandidates(positiveInt(budget.get("maxRefinedCandidates"),
                    "maxRefinedCandidates", file), priority, source);
        }
        if (budget.has("maxNormalPatternsPerTarget")) {
            target.maxNormalPatternsPerTarget(positiveInt(budget.get("maxNormalPatternsPerTarget"),
                    "maxNormalPatternsPerTarget", file), priority, source);
        }
        if (budget.has("maxInputAlternatives")) {
            target.maxInputAlternatives(positiveInt(budget.get("maxInputAlternatives"), "maxInputAlternatives", file),
                    priority, source);
        }
        if (budget.has("maxRouteDepth")) {
            target.maxRouteDepth(positiveInt(budget.get("maxRouteDepth"), "maxRouteDepth", file), priority, source);
        }
        if (budget.has("maxRouteExpansionsPerTarget")) {
            target.maxRouteExpansionsPerTarget(positiveInt(budget.get("maxRouteExpansionsPerTarget"),
                    "maxRouteExpansionsPerTarget", file), priority, source);
        }
        if (budget.has("maxRouteExpansionsPerCalculation")) {
            target.maxRouteExpansionsPerCalculation(positiveInt(budget.get("maxRouteExpansionsPerCalculation"),
                    "maxRouteExpansionsPerCalculation", file), priority, source);
        }
        if (budget.has("maxRouteCalculationMillis")) {
            target.maxRouteCalculationMillis(positiveLong(budget.get("maxRouteCalculationMillis"),
                    "maxRouteCalculationMillis", file), priority, source);
        }
        if (budget.has("maxStandaloneRouteExpansionsPerCalculation")) {
            target.maxStandaloneRouteExpansionsPerCalculation(positiveInt(
                    budget.get("maxStandaloneRouteExpansionsPerCalculation"),
                    "maxStandaloneRouteExpansionsPerCalculation", file), priority, source);
        }
        if (budget.has("maxStandaloneRouteCalculationMillis")) {
            target.maxStandaloneRouteCalculationMillis(positiveLong(
                    budget.get("maxStandaloneRouteCalculationMillis"),
                    "maxStandaloneRouteCalculationMillis", file), priority, source);
        }
        if (budget.has("maxSccNodes")) {
            target.maxSccNodes(positiveInt(budget.get("maxSccNodes"), "maxSccNodes", file), priority, source);
        }
        if (budget.has("maxSccEdges")) {
            target.maxSccEdges(positiveInt(budget.get("maxSccEdges"), "maxSccEdges", file), priority, source);
        }
        if (budget.has("maxSccAnalysisMillis")) {
            target.maxSccAnalysisMillis(positiveLong(budget.get("maxSccAnalysisMillis"),
                    "maxSccAnalysisMillis", file), priority, source);
        }
        if (budget.has("onExhaustion")) {
            try {
                target.exhaustionPolicy(BudgetExhaustionPolicy.valueOf(stringValue(budget.get("onExhaustion"),
                        "planningBudget.onExhaustion", file)), priority, source);
            } catch (IllegalArgumentException exception) {
                throw new IOException("Unknown planningBudget.onExhaustion in " + file, exception);
            }
        }
        if (budget.has("cycleSafetyOnExhaustion")) {
            try {
                target.cycleSafetyExhaustionPolicy(CycleSafetyExhaustionPolicy.valueOf(stringValue(
                        budget.get("cycleSafetyOnExhaustion"), "planningBudget.cycleSafetyOnExhaustion", file)),
                        priority, source);
            } catch (IllegalArgumentException exception) {
                throw new IOException("Unknown planningBudget.cycleSafetyOnExhaustion in " + file, exception);
            }
        }
    }

    private static CompiledRule parseRule(JsonElement element, Path file) throws IOException {
        if (!element.isJsonObject()) throw new IOException("Rule must be an object in " + file);
        JsonObject rule = element.getAsJsonObject();
        requireOnlyFields(rule, Set.of("id", "priority", "when", "effects"), "rule", file);
        String id = requiredString(rule, "id", file);
        int priority = optionalInt(rule, "priority", 0, file);
        RulePredicate predicate = rule.has("when") ? parsePredicate(rule.get("when"), file) : context -> true;
        if (!rule.has("effects") || !rule.get("effects").isJsonArray()) {
            throw new IOException("Rule " + id + " must have an effects array");
        }
        List<RuleEffect> effects = new ArrayList<>();
        for (JsonElement effectElement : rule.getAsJsonArray("effects")) {
            effects.addAll(parseEffects(effectElement, file, id));
        }
        if (effects.isEmpty()) throw new IOException("Rule " + id + " has no effects");
        return new CompiledRule(id, priority, predicate, effects);
    }

    private static RulePredicate parsePredicate(JsonElement element, Path file) throws IOException {
        if (!element.isJsonObject()) throw new IOException("Rule predicate must be an object in " + file);
        JsonObject object = element.getAsJsonObject();
        if (object.entrySet().isEmpty()) throw new IOException("Rule predicate must not be empty in " + file);

        int combinators = (object.has("all") ? 1 : 0) + (object.has("any") ? 1 : 0) +
                (object.has("not") ? 1 : 0);
        if (combinators > 0) {
            if (combinators != 1 || object.entrySet().size() != 1) {
                throw new IOException("all, any, and not predicates cannot be combined in " + file);
            }
            if (object.has("all")) return combine(object.get("all"), file, true);
            if (object.has("any")) return combine(object.get("any"), file, false);
            RulePredicate nested = parsePredicate(object.get("not"), file);
            return context -> !nested.matches(context);
        }

        List<RulePredicate> predicates = new ArrayList<>();
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            String key = entry.getKey();
            JsonElement value = entry.getValue();
            switch (key) {
                case "recipeMap" -> {
                    List<String> expected = stringValues(value, "recipeMap", file);
                    predicates.add(context -> expected.contains(context.getRecipe().getRecipeMapId()));
                }
                case "category" -> {
                    List<String> expected = stringValues(value, "category", file);
                    predicates.add(context -> expected.contains(context.getRecipe().getCategory()));
                }
                case "target" -> {
                    List<String> expected = stringValues(value, "target", file);
                    predicates.add(context -> expected.contains(RecipeFingerprint.describeKey(context.getTarget())));
                }
                case "tag" -> {
                    List<String> expected = stringValues(value, "tag", file);
                    predicates.add(context -> anyMatches(expected, context.getTags()));
                }
                case "capability" -> {
                    List<String> expected = stringValues(value, "capability", file);
                    predicates.add(context -> anyString(expected, context::hasCapability));
                }
                case "planningMode" -> {
                    List<String> expected = stringValues(value, "planningMode", file);
                    predicates.add(context -> expected.contains(context.getPlanningMode().name()));
                }
                case "hasChancedOutputs" -> {
                    boolean expected = booleanValue(value, file);
                    predicates.add(context -> expected == context.getRecipe().hasChancedOutputs());
                }
                case "hiddenOutputAtLeast" -> {
                    int minimum = intValue(value, file);
                    predicates.add(context -> context.getHiddenOutputCount() >= minimum);
                }
                case "tokenSlotsAtLeast" -> {
                    int minimum = intValue(value, file);
                    predicates.add(context -> context.getTokenSlots() >= minimum);
                }
                case "tokenSlotsAtMost" -> {
                    int maximum = intValue(value, file);
                    predicates.add(context -> context.getTokenSlots() <= maximum);
                }
                case "property" -> predicates.add(parsePropertyPredicate(value, file));
                case "fact" -> predicates.add(parseFactPredicate(value, file));
                default -> throw new IOException("Unknown rule predicate " + key + " in " + file);
            }
        }
        return context -> {
            for (RulePredicate predicate : predicates) {
                if (!predicate.matches(context)) return false;
            }
            return true;
        };
    }

    private static RulePredicate combine(JsonElement element, Path file, boolean all) throws IOException {
        if (!element.isJsonArray()) throw new IOException("all/any must be an array in " + file);
        List<RulePredicate> predicates = new ArrayList<>();
        for (JsonElement child : element.getAsJsonArray()) predicates.add(parsePredicate(child, file));
        if (predicates.isEmpty()) throw new IOException("all/any must not be empty in " + file);
        return context -> {
            for (RulePredicate predicate : predicates) {
                if (predicate.matches(context) != all) return !all;
            }
            return all;
        };
    }

    private static RulePredicate parsePropertyPredicate(JsonElement element, Path file) throws IOException {
        if (!element.isJsonObject()) throw new IOException("property predicate must be an object in " + file);
        JsonObject property = element.getAsJsonObject();
        requireOnlyFields(property, Set.of("key", "exists", "equals", "notEquals", "atLeast", "atMost", "in"),
                "property predicate", file);
        String key = requiredString(property, "key", file);
        int operations = countFields(property, "exists", "equals", "notEquals", "atLeast", "atMost", "in");
        if (operations != 1) {
            throw new IOException("property predicate must declare exactly one comparison in " + file);
        }
        if (property.has("exists")) {
            boolean exists = booleanValue(property.get("exists"), file);
            return context -> context.getRecipe().getProperties().containsKey(key) == exists;
        }
        if (property.has("equals")) {
            String equals = stringValue(property.get("equals"), "property.equals", file);
            return context -> equals.equals(context.getRecipe().getProperties().get(key));
        }
        if (property.has("notEquals")) {
            String notEquals = stringValue(property.get("notEquals"), "property.notEquals", file);
            return context -> !notEquals.equals(context.getRecipe().getProperties().get(key));
        }
        if (property.has("atLeast")) {
            long minimum = longValue(property.get("atLeast"), file);
            return context -> numberAtLeast(context.getRecipe().getNumericProperty(key), minimum);
        }
        if (property.has("atMost")) {
            long maximum = longValue(property.get("atMost"), file);
            return context -> numberAtMost(context.getRecipe().getNumericProperty(key), maximum);
        }
        List<String> expected = stringValues(property.get("in"), "property.in", file);
        return context -> expected.contains(context.getRecipe().getProperties().get(key));
    }

    private static RulePredicate parseFactPredicate(JsonElement element, Path file) throws IOException {
        if (!element.isJsonObject()) throw new IOException("fact predicate must be an object in " + file);
        JsonObject fact = element.getAsJsonObject();
        requireOnlyFields(fact, Set.of("key", "exists", "equals", "notEquals", "atLeast", "atMost", "in",
                        "contains"),
                "fact predicate", file);
        String key = requiredString(fact, "key", file);
        int operations = countFields(fact, "exists", "equals", "notEquals", "atLeast", "atMost", "in",
                "contains");
        if (operations != 1) {
            throw new IOException("fact predicate must declare exactly one comparison in " + file);
        }
        if (fact.has("exists")) {
            boolean exists = booleanValue(fact.get("exists"), file);
            return context -> (context.getFact(key) != null) == exists;
        }
        if (fact.has("equals")) {
            JsonElement expected = scalarValue(fact.get("equals"), "fact.equals", file);
            return context -> valueMatches(context.getFact(key), expected);
        }
        if (fact.has("notEquals")) {
            JsonElement expected = scalarValue(fact.get("notEquals"), "fact.notEquals", file);
            return context -> !valueMatches(context.getFact(key), expected);
        }
        if (fact.has("atLeast")) {
            long minimum = longValue(fact.get("atLeast"), file);
            return context -> numberAtLeast(toLong(context.getFact(key)), minimum);
        }
        if (fact.has("atMost")) {
            long maximum = longValue(fact.get("atMost"), file);
            return context -> numberAtMost(toLong(context.getFact(key)), maximum);
        }
        if (fact.has("contains")) {
            JsonElement expected = scalarValue(fact.get("contains"), "fact.contains", file);
            return context -> collectionContains(context.getFact(key), expected);
        }
        if (fact.has("in")) {
            JsonElement expected = fact.get("in");
            if (!expected.isJsonArray()) throw new IOException("fact.in must be an array in " + file);
            List<JsonElement> expectedValues = new ArrayList<>();
            for (JsonElement candidate : expected.getAsJsonArray()) {
                expectedValues.add(scalarValue(candidate, "fact.in", file));
            }
            if (expectedValues.isEmpty()) throw new IOException("fact.in must not be empty in " + file);
            return context -> {
                for (JsonElement candidate : expectedValues) {
                    if (valueMatches(context.getFact(key), candidate)) return true;
                }
                return false;
            };
        }
        throw new IOException("Unreachable fact predicate parse state in " + file);
    }

    private static List<RuleEffect> parseEffects(JsonElement element, Path file, String ruleId) throws IOException {
        if (!element.isJsonObject()) throw new IOException("Effect must be an object in rule " + ruleId);
        JsonObject effect = element.getAsJsonObject();
        if (effect.entrySet().isEmpty()) throw new IOException("Effect must not be empty in rule " + ruleId);
        List<RuleEffect> effects = new ArrayList<>();
        for (Map.Entry<String, JsonElement> entry : effect.entrySet()) {
            String key = entry.getKey();
            JsonElement value = entry.getValue();
            switch (key) {
                case "deny" -> {
                    String code = stringValue(value, "deny", file);
                    effects.add((id, priority, context, decision) -> decision.deny(id, code, priority, false));
                }
                case "allow" -> {
                    boolean enabled = booleanValue(value, file);
                    if (enabled) effects.add((id, priority, context, decision) -> decision.allow(id, priority));
                }
                case "tag" -> {
                    String tag = stringValue(value, "tag", file);
                    effects.add((id, priority, context, decision) -> decision.tag(id, tag));
                }
                case "requireCapability" -> {
                    String capability = stringValue(value, "requireCapability", file);
                    effects.add((id, priority, context, decision) -> {
                    if (!context.hasCapability(capability)) {
                            decision.deny(id, "MISSING_CAPABILITY_" + capability.toUpperCase(), priority, false);
                    }
                    });
                }
                case "denyHiddenOutput" -> {
                    boolean denyHiddenOutput = booleanValue(value, file);
                    effects.add((id, priority, context, decision) -> {
                    if (denyHiddenOutput && context.getHiddenOutputCount() > 0) {
                            decision.deny(id, "HIDDEN_OUTPUT_DENIED", priority, false);
                    }
                    });
                }
                case "score", "penalize" -> effects.addAll(parseScoreEffects(value, file,
                        key.equals("penalize") ? -1 : 1));
                case "expose" -> effects.add(parseExposeEffect(value, file));
                case "outputPolicy" -> effects.add(parseOutputPolicyEffect(value, file));
                case "cyclePolicy" -> effects.add(parseCyclePolicyEffect(value, file));
                case "planningMode" -> effects.add(parsePlanningModeEffect(value, file));
                case "pinGroup" -> {
                    String group = stringValue(value, "pinGroup", file);
                    effects.add((id, priority, context, decision) -> decision.pinGroup(id, group, priority));
                }
                default -> throw new IOException("Unknown rule effect " + key + " in rule " + ruleId);
            }
        }
        return effects;
    }

    private static List<RuleEffect> parseScoreEffects(JsonElement element, Path file, int sign) throws IOException {
        if (!element.isJsonObject()) throw new IOException("score and penalize must be objects in " + file);
        List<RuleEffect> effects = new ArrayList<>();
        for (Map.Entry<String, JsonElement> score : element.getAsJsonObject().entrySet()) {
            String scoreName = score.getKey();
            long value = longValue(score.getValue(), file);
            try {
                long signed = Math.multiplyExact(sign, value);
                effects.add((id, priority, context, decision) -> decision.score(id, scoreName, signed));
            } catch (ArithmeticException exception) {
                throw new IOException("Score " + score.getKey() + " overflows in " + file, exception);
            }
        }
        return effects;
    }

    private static RuleEffect parseExposeEffect(JsonElement element, Path file) throws IOException {
        if (!element.isJsonObject() || !element.getAsJsonObject().has("maxPatternsForTarget")) {
            throw new IOException("expose must contain maxPatternsForTarget in " + file);
        }
        JsonObject expose = element.getAsJsonObject();
        requireOnlyFields(expose, Set.of("maxPatternsForTarget"), "expose effect", file);
        int maximum = intValue(expose.get("maxPatternsForTarget"), file);
        return (id, priority, context, decision) -> decision.capPatterns(id, maximum);
    }

    private static RuleEffect parseOutputPolicyEffect(JsonElement element, Path file) throws IOException {
        OutputPolicy policy;
        try {
            policy = OutputPolicy.valueOf(stringValue(element, "outputPolicy", file));
        } catch (RuntimeException exception) {
            throw new IOException("Unknown output policy in " + file, exception);
        }
        return (id, priority, context, decision) -> decision.outputPolicy(id, policy, priority);
    }

    private static RuleEffect parseCyclePolicyEffect(JsonElement element, Path file) throws IOException {
        CyclePolicy policy;
        try {
            policy = CyclePolicy.valueOf(stringValue(element, "cyclePolicy", file));
        } catch (RuntimeException exception) {
            throw new IOException("Unknown cycle policy in " + file, exception);
        }
        return (id, priority, context, decision) -> decision.cyclePolicy(id, policy, priority);
    }

    private static RuleEffect parsePlanningModeEffect(JsonElement element, Path file) throws IOException {
        PlanningMode mode;
        try {
            mode = PlanningMode.valueOf(stringValue(element, "planningMode", file));
        } catch (RuntimeException exception) {
            throw new IOException("Unknown planning mode in " + file, exception);
        }
        return (id, priority, context, decision) -> decision.planningMode(id, mode, priority);
    }

    private static String requiredString(JsonObject object, String key, Path file) throws IOException {
        if (!object.has(key) || !object.get(key).isJsonPrimitive() ||
                !object.getAsJsonPrimitive(key).isString()) {
            throw new IOException("Missing string " + key + " in " + file);
        }
        String value = object.get(key).getAsString();
        if (value.isEmpty()) throw new IOException("String " + key + " must not be empty in " + file);
        return value;
    }

    private static String stringValue(JsonElement element, String name, Path file) throws IOException {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new IOException("Expected string " + name + " in " + file);
        }
        try {
            return element.getAsString();
        } catch (RuntimeException exception) {
            throw new IOException("Expected string " + name + " in " + file, exception);
        }
    }

    private static int optionalInt(JsonObject object, String key, int fallback, Path file) throws IOException {
        return object.has(key) ? intValue(object.get(key), file) : fallback;
    }

    private static int intValue(JsonElement element, Path file) throws IOException {
        long value = longValue(element, file);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new IOException("Expected 32-bit integer in " + file);
        }
        return (int) value;
    }

    private static int positiveInt(JsonElement element, String name, Path file) throws IOException {
        int value = intValue(element, file);
        if (value <= 0) throw new IOException(name + " must be positive in " + file);
        return value;
    }

    private static long positiveLong(JsonElement element, String name, Path file) throws IOException {
        long value = longValue(element, file);
        if (value <= 0) throw new IOException(name + " must be positive in " + file);
        return value;
    }

    private static boolean booleanValue(JsonElement element, Path file) throws IOException {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean()) {
            throw new IOException("Expected boolean in " + file);
        }
        try {
            return element.getAsBoolean();
        } catch (RuntimeException exception) {
            throw new IOException("Expected boolean in " + file, exception);
        }
    }

    private static boolean anyMatches(Collection<String> expected, Collection<String> values) {
        for (String value : values) {
            if (expected.contains(value)) return true;
        }
        return false;
    }

    private static boolean anyString(Collection<String> expected, java.util.function.Predicate<String> predicate) {
        for (String candidate : expected) {
            if (predicate.test(candidate)) return true;
        }
        return false;
    }

    private static long longValue(JsonElement element, Path file) throws IOException {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new IOException("Expected integer in " + file);
        }
        try {
            BigDecimal value = element.getAsBigDecimal();
            return value.longValueExact();
        } catch (RuntimeException exception) {
            throw new IOException("Expected integer in " + file, exception);
        }
    }

    private static List<String> stringValues(JsonElement element, String name, Path file) throws IOException {
        if (element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
            return List.of(stringValue(element, name, file));
        }
        if (element == null || !element.isJsonArray()) {
            throw new IOException("Expected string or string array " + name + " in " + file);
        }
        List<String> values = new ArrayList<>();
        for (JsonElement candidate : element.getAsJsonArray()) {
            values.add(stringValue(candidate, name, file));
        }
        if (values.isEmpty()) throw new IOException("String array " + name + " must not be empty in " + file);
        return Collections.unmodifiableList(values);
    }

    private static JsonElement scalarValue(JsonElement element, String name, Path file) throws IOException {
        if (element == null || !element.isJsonPrimitive()) {
            throw new IOException("Expected scalar " + name + " in " + file);
        }
        return element;
    }

    private static int countFields(JsonObject object, String... keys) {
        int count = 0;
        for (String key : keys) {
            if (object.has(key)) count++;
        }
        return count;
    }

    private static void requireOnlyFields(JsonObject object, Set<String> allowed, String structure, Path file)
            throws IOException {
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            if (!allowed.contains(entry.getKey())) {
                throw new IOException("Unknown " + structure + " field " + entry.getKey() + " in " + file);
            }
        }
    }

    private static boolean valueMatches(Object actual, JsonElement expected) {
        if (actual == null || expected == null || !expected.isJsonPrimitive()) return false;
        if (expected.getAsJsonPrimitive().isBoolean()) {
            return actual instanceof Boolean value ? value == expected.getAsBoolean() :
                    String.valueOf(actual).equals(String.valueOf(expected.getAsBoolean()));
        }
        if (expected.getAsJsonPrimitive().isNumber()) {
            Long actualNumber = toLong(actual);
            return actualNumber != null && actualNumber == expected.getAsLong();
        }
        return String.valueOf(actual).equals(expected.getAsString());
    }

    private static boolean collectionContains(Object actual, JsonElement expected) {
        if (!(actual instanceof Collection<?> values)) return false;
        for (Object value : values) {
            if (valueMatches(value, expected)) return true;
        }
        return false;
    }

    private static boolean numberAtLeast(Long actual, long minimum) {
        return actual != null && actual >= minimum;
    }

    private static boolean numberAtMost(Long actual, long maximum) {
        return actual != null && actual <= maximum;
    }

    private static Long toLong(Object value) {
        if (value instanceof Number number) return number.longValue();
        if (value == null) return null;
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
