package applygray;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import applygray.mattermanipulator.item.ManipulatorComponent;
import applygray.mattermanipulator.state.ManipulatorPendingAction;
import applygray.mattermanipulator.state.ManipulatorPlaceMode;
import applygray.mattermanipulator.state.ManipulatorRemovalMode;
import applygray.mattermanipulator.state.ManipulatorShape;
import applygray.mattermanipulator.state.ManipulatorTier;
import applygray.mattermanipulator.state.ManipulatorUpgrade;

class LocalizationResourceTest {

    private static final Path EN_US = Path.of("src/main/resources/assets/applygray/lang/en_us.lang");
    private static final Path ZH_CN = Path.of("src/main/resources/assets/applygray/lang/zh_cn.lang");
    private static final Path JAVA_SOURCES = Path.of("src/main/java/applygray");
    private static final Path META_TILE_ENTITIES =
            JAVA_SOURCES.resolve("common/ApplyGrayMetaTileEntities.java");

    private static final Pattern TRANSLATION_CALL = Pattern.compile(
            "(?s)(?<![A-Za-z0-9_$])(?:new\\s+(?:[A-Za-z0-9_$.]+\\.)?TextComponentTranslation|"
                    + "I18n\\.format|translate|action|branch|entry)\\s*\\(\\s*\""
                    + "((?:item\\.|key\\.)?applygray\\.[A-Za-z0-9_.-]+)\"");
    private static final Pattern EXACT_META_TILE_ID =
            Pattern.compile("ApplyGrayAPI\\.id\\(\"([A-Za-z0-9_.-]+)\"\\)");

    @Test
    void languageFilesHaveMatchingUniqueKeys() throws IOException {
        LanguageFile english = readLanguage(EN_US);
        LanguageFile chinese = readLanguage(ZH_CN);

        assertTrue(english.duplicates().isEmpty(), "Duplicate en_us keys: " + english.duplicates());
        assertTrue(chinese.duplicates().isEmpty(), "Duplicate zh_cn keys: " + chinese.duplicates());
        assertEquals(english.values().keySet(), chinese.values().keySet(), "Language key sets differ");
    }

    @Test
    void registeredContentAndTranslationCallsHaveLanguageEntries() throws IOException {
        Set<String> required = sourceTranslationKeys();
        required.addAll(generatedItemKeys());
        required.addAll(generatedEnumKeys());
        required.addAll(exactMetaTileEntityKeys());

        assertContainsAll(EN_US, required);
        assertContainsAll(ZH_CN, required);
    }

    private static Set<String> sourceTranslationKeys() throws IOException {
        Set<String> keys = new HashSet<>();
        try (Stream<Path> files = Files.walk(JAVA_SOURCES)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                Matcher matcher = TRANSLATION_CALL.matcher(Files.readString(file, StandardCharsets.UTF_8));
                while (matcher.find()) {
                    String key = matcher.group(1);
                    if (!key.endsWith(".") && !key.endsWith("_")) {
                        keys.add(key);
                    }
                }
            }
        }
        return keys;
    }

    private static Set<String> generatedItemKeys() {
        Set<String> keys = new HashSet<>();
        for (ManipulatorTier tier : ManipulatorTier.values()) {
            keys.add("item.applygray.matter_manipulator_" + lower(tier) + ".name");
        }
        for (ManipulatorComponent component : ManipulatorComponent.values()) {
            keys.add("item.applygray." + component.registryPath() + ".name");
        }
        for (ManipulatorUpgrade upgrade : ManipulatorUpgrade.values()) {
            keys.add(upgrade.translationKey());
        }
        return keys;
    }

    private static Set<String> generatedEnumKeys() {
        Set<String> keys = new HashSet<>();
        addEnumKeys(keys, "mode", ManipulatorPlaceMode.values());
        addEnumKeys(keys, "removal", ManipulatorRemovalMode.values());
        addEnumKeys(keys, "shape", ManipulatorShape.values());
        addEnumKeys(keys, "pending_action", ManipulatorPendingAction.values());
        return keys;
    }

    private static Set<String> exactMetaTileEntityKeys() throws IOException {
        Set<String> keys = new HashSet<>();
        Matcher matcher = EXACT_META_TILE_ID.matcher(Files.readString(META_TILE_ENTITIES, StandardCharsets.UTF_8));
        while (matcher.find()) {
            keys.add("applygray.machine." + matcher.group(1) + ".name");
        }
        return keys;
    }

    private static void addEnumKeys(Set<String> keys, String category, Enum<?>[] values) {
        for (Enum<?> value : values) {
            keys.add("applygray.matter_manipulator." + category + "." + lower(value));
        }
    }

    private static String lower(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }

    private static void assertContainsAll(Path path, Set<String> required) throws IOException {
        Set<String> available = readLanguage(path).values().keySet();
        List<String> missing = new ArrayList<>(required);
        missing.removeAll(available);
        missing.sort(String::compareTo);
        assertTrue(missing.isEmpty(), path + " is missing translations: " + missing);
    }

    private static LanguageFile readLanguage(Path path) throws IOException {
        Map<String, String> values = new LinkedHashMap<>();
        Set<String> duplicates = new HashSet<>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;

            int separator = line.indexOf('=');
            if (separator <= 0) continue;

            String key = line.substring(0, separator).trim();
            String value = line.substring(separator + 1);
            if (values.put(key, value) != null) {
                duplicates.add(key);
            }
        }
        return new LanguageFile(values, duplicates);
    }

    private record LanguageFile(Map<String, String> values, Set<String> duplicates) {}
}
