// Sanity-checks the Extera Light/Dark Monet assets before a PR. Not a build gate - there is no
// test source set under TMessagesProj/src, so this is a standalone script (JDK 21 single-file
// source launch, no build step, no dependencies), the same way IconInk.java in this directory is.
//
// It checks five things, since a mistyped, dropped, or unrecognized token is invisible to
// eyeballing a themed screen:
//
//   1. monet_extera_{light,dark}.attheme carry every key their monet_{light,dark}.attheme
//      counterpart does (a superset), so nothing silently falls back to the XML default.
//   2. every key in the two assets is one ThemeColors.stringKeyToInt() actually resolves -
//      parsed straight out of ThemeColors.java's colorKeysMap, the sole map that backs it - or is
//      an unknown key already present verbatim in the corresponding baseline asset (a pre-existing
//      bug this change didn't introduce and isn't the place to fix). Theme.getThemeFileValues()
//      silently drops any key stringKeyToInt() can't resolve (keyFromString >= 0 guard), so a typo'd
//      or renamed key is a complete no-op at runtime with nothing to show for it on screen.
//   3. every value in the two assets either matches a valid 6- or 8-digit '#' hex color, is
//      a raw ARGB int literal, or resolves against MonetHelper's own token vocabulary - parsed
//      straight out of MonetHelper.java so this can never drift from what the app actually resolves
//      at runtime. 0 doubles as MonetHelper's own "failed to resolve" sentinel (see getColor()'s
//      catch clause), so a bad token and an intentional zero both render identically and neither
//      eyeballing a themed screen nor a runtime log line can tell them apart after the fact.
//   4. the Extera Light and Dark assets carry the exact same set of keys once restricted to names
//      ThemeColors actually recognizes. The raw files are allowed to differ - Extera Light alone
//      inherits an unrelated pre-existing typo'd key (kvoipgroup_overlayAlertMutedByAdmin2) that
//      isn't a recognized key either way, so it drops out of both sides of this comparison rather
//      than needing its own hand-carved exception - but a recognized key present in only one of the
//      two variants would mean that surface silently falls back to the XML default in just one of
//      Light/Dark, which is exactly as invisible on a themed screen as check 2's dropped keys.
//   5. every raw decimal literal in the two assets is one Integer.parseInt() actually
//      accepts, matching the runtime path exactly: Utilities.parseInt(CharSequence) - what
//      Theme.getThemeFileValues() calls for a raw decimal attheme value - extracts the leading
//      "-?[0-9]+" run and hands it straight to Integer.parseInt(), silently returning 0 if that
//      throws (out-of-range or otherwise). A value outside the signed 32-bit range is exactly as
//      invisible on a themed screen as an unresolved token resolving to 0.
//
// Run with no build step and no dependencies (JDK 21 single-file source launch), from the repo
// root, and paste the output in the PR body:
//
//     java Tools/scripts/VerifyExteraThemes.java

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class VerifyExteraThemes {
    private static final Path ASSETS = Paths.get("TMessagesProj/src/main/assets");
    private static final Path MONET_HELPER = Paths.get("TMessagesProj/src/main/java/tw/nekomimi/nekogram/helpers/MonetHelper.java");
    private static final Path THEME_COLORS = Paths.get("TMessagesProj/src/main/java/org/telegram/ui/ActionBar/ThemeColors.java");
    private static final Pattern HEX_COLOR = Pattern.compile("^#([0-9A-Fa-f]{6}|[0-9A-Fa-f]{8})$");

    public static void main(String[] args) throws IOException {
        Set<String> vocabulary = loadVocabulary(MONET_HELPER);
        System.out.println("Loaded " + vocabulary.size() + " tokens from " + MONET_HELPER);
        Set<String> validKeys = loadValidKeys(THEME_COLORS);
        System.out.println("Loaded " + validKeys.size() + " runtime key names from " + THEME_COLORS);

        boolean ok = true;
        ok &= checkSuperset("monet_light.attheme", "monet_extera_light.attheme");
        ok &= checkSuperset("monet_dark.attheme", "monet_extera_dark.attheme");
        ok &= checkKeys("monet_light.attheme", "monet_extera_light.attheme", validKeys);
        ok &= checkKeys("monet_dark.attheme", "monet_extera_dark.attheme", validKeys);
        ok &= checkValues("monet_extera_light.attheme", vocabulary);
        ok &= checkValues("monet_extera_dark.attheme", vocabulary);
        ok &= checkValidKeyParity("monet_extera_light.attheme", "monet_extera_dark.attheme", validKeys);
        ok &= checkInt32RoundTrip("monet_extera_light.attheme");
        ok &= checkInt32RoundTrip("monet_extera_dark.attheme");
        // Gboard Light/Dark are the Extera assets with two panel colors swapped in.
        ok &= checkSuperset("monet_extera_light.attheme", "monet_gboard_light.attheme");
        ok &= checkSuperset("monet_extera_dark.attheme", "monet_gboard_dark.attheme");
        ok &= checkValues("monet_gboard_light.attheme", vocabulary);
        ok &= checkValues("monet_gboard_dark.attheme", vocabulary);

        System.out.println(ok ? "OK" : "FAILED");
        if (!ok) {
            System.exit(1);
        }
    }

    // ThemeColors.stringKeyToInt() is built entirely from colorKeysMap's put(key_x, "name") calls
    // (createColorKeysStringMap() just inverts that same map), so this regex is exact - it can't
    // drift from what the runtime actually resolves.
    private static Set<String> loadValidKeys(Path themeColorsJava) throws IOException {
        String source = Files.readString(themeColorsJava, StandardCharsets.UTF_8);
        Set<String> keys = new TreeSet<>();
        Matcher m = Pattern.compile("colorKeysMap\\.put\\([A-Za-z0-9_]+,\\s*\"([^\"]+)\"\\)").matcher(source);
        while (m.find()) {
            keys.add(m.group(1));
        }
        return keys;
    }

    // MonetHelper's `ids` and `harmonizedBaseColors` maps are both just chains of put("token", ...);
    // this is the only place either map is populated, so a plain regex over the whole file is
    // exact and needs no Java parser.
    private static Set<String> loadVocabulary(Path monetHelperJava) throws IOException {
        String source = Files.readString(monetHelperJava, StandardCharsets.UTF_8);
        Set<String> tokens = new TreeSet<>();
        Matcher m = Pattern.compile("put\\(\"([A-Za-z0-9_]+)\"").matcher(source);
        while (m.find()) {
            tokens.add(m.group(1));
        }
        return tokens;
    }

    private static Map<String, String> parseAttheme(Path file) throws IOException {
        Map<String, String> values = new LinkedHashMap<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isEmpty() || line.equals("end") || line.startsWith("//")) {
                continue;
            }
            int idx = line.indexOf('=');
            if (idx < 0) {
                continue;
            }
            values.put(line.substring(0, idx), line.substring(idx + 1));
        }
        return values;
    }

    private static boolean checkSuperset(String baseName, String extraName) throws IOException {
        Map<String, String> base = parseAttheme(ASSETS.resolve(baseName));
        Map<String, String> extra = parseAttheme(ASSETS.resolve(extraName));
        List<String> missing = base.keySet().stream()
                .filter(k -> !extra.containsKey(k))
                .sorted()
                .collect(Collectors.toList());
        if (!missing.isEmpty()) {
            System.out.println(extraName + " is missing " + missing.size() + " key(s) from " + baseName + ": " + missing);
            return false;
        }
        System.out.println(extraName + ": superset of " + baseName + " (" + extra.size() + " >= " + base.size() + " keys)");
        return true;
    }

    // A key this change introduces that stringKeyToInt() can't resolve is a silent no-op at
    // runtime (Theme.getThemeFileValues()' keyFromString >= 0 guard just drops it). A key already
    // present under the same unresolved name in the baseline asset is a pre-existing bug this
    // change inherited rather than introduced, so it's tolerated rather than failed here - fixing
    // the base file is out of scope for this script.
    private static boolean checkKeys(String baseName, String extraName, Set<String> validKeys) throws IOException {
        Map<String, String> base = parseAttheme(ASSETS.resolve(baseName));
        Map<String, String> extra = parseAttheme(ASSETS.resolve(extraName));
        List<String> newlyInvalid = extra.keySet().stream()
                .filter(k -> !validKeys.contains(k) && !base.containsKey(k))
                .sorted()
                .collect(Collectors.toList());
        if (!newlyInvalid.isEmpty()) {
            System.out.println(extraName + " has " + newlyInvalid.size() + " key(s) unknown to ThemeColors and not inherited from "
                    + baseName + " (dropped silently at runtime): " + newlyInvalid);
            return false;
        }
        List<String> inheritedInvalid = extra.keySet().stream()
                .filter(k -> !validKeys.contains(k))
                .sorted()
                .collect(Collectors.toList());
        if (!inheritedInvalid.isEmpty()) {
            System.out.println(extraName + ": " + inheritedInvalid.size() + " key(s) unknown to ThemeColors, inherited verbatim from "
                    + baseName + " (pre-existing, not introduced here): " + inheritedInvalid);
        }
        System.out.println(extraName + ": no newly-introduced unresolvable keys");
        return true;
    }

    // Mirrors MonetHelper.getColor()'s own parse order: strip a trailing (NN) alpha suffix first,
    // then check the remainder for a trailing _NN darken suffix, same as the resolver does.
    private static boolean isResolvable(String value, Set<String> vocabulary) {
        String v = value;
        if (HEX_COLOR.matcher(v).matches() || v.matches("-?\\d+")) {
            return true;
        }
        if (v.startsWith("#")) {
            // Wrong-length/invalid hex: Color.parseColor() throws, falls back to
            // Utilities.parseInt() (likely 0) - same silent failure class as a bad token.
            return false;
        }
        if (!(v.startsWith("a") || v.startsWith("n") || v.startsWith("monet"))) {
            // Theme.getThemeFileValues() only hands values with these prefixes to MonetHelper;
            // anything else goes to Utilities.parseInt() and becomes 0 (#gboard-themes).
            return false;
        }

        Matcher alpha = Pattern.compile("^(.*)\\((\\d{1,3})\\)$").matcher(v);
        if (alpha.matches()) {
            int percent;
            try {
                percent = Integer.parseInt(alpha.group(2));
            } catch (NumberFormatException e) {
                return false;
            }
            if (percent < 0 || percent > 100) {
                return false;
            }
            v = alpha.group(1);
        }

        Matcher darken = Pattern.compile("^(.+)_(\\d+)$").matcher(v);
        if (darken.matches() && vocabulary.contains(darken.group(1))) {
            // getColor() does Integer.parseInt(darkenPercentValue) with no bounds guard of its
            // own - a digit run long enough to overflow int throws, gets swallowed by getColor()'s
            // catch-all, and silently resolves to 0, exactly like an unknown token would.
            try {
                Integer.parseInt(darken.group(2));
            } catch (NumberFormatException e) {
                return false;
            }
            v = darken.group(1);
        }

        return vocabulary.contains(v);
    }

    private static boolean checkValues(String assetName, Set<String> vocabulary) throws IOException {
        Map<String, String> values = parseAttheme(ASSETS.resolve(assetName));
        List<String> bad = new ArrayList<>();
        for (Map.Entry<String, String> e : values.entrySet()) {
            if (!isResolvable(e.getValue(), vocabulary)) {
                bad.add(e.getKey() + "=" + e.getValue());
            }
        }
        if (!bad.isEmpty()) {
            System.out.println(assetName + " has " + bad.size() + " unresolvable value(s): " + bad);
            return false;
        }
        System.out.println(assetName + ": all " + values.size() + " values resolve");
        return true;
    }

    // Check 4: the two Extera variants must expose the same set of ThemeColors-recognized keys.
    // Restricting to validKeys first (rather than diffing the raw key sets) is what lets this skip
    // a hand-written exception for the pre-existing kvoipgroup_overlayAlertMutedByAdmin2 typo that
    // only Light's raw file carries - it isn't a recognized key on either side, so it never enters
    // either set being compared.
    private static boolean checkValidKeyParity(String lightName, String darkName, Set<String> validKeys) throws IOException {
        Map<String, String> light = parseAttheme(ASSETS.resolve(lightName));
        Map<String, String> dark = parseAttheme(ASSETS.resolve(darkName));
        Set<String> lightValid = light.keySet().stream().filter(validKeys::contains).collect(Collectors.toCollection(TreeSet::new));
        Set<String> darkValid = dark.keySet().stream().filter(validKeys::contains).collect(Collectors.toCollection(TreeSet::new));
        List<String> onlyInLight = lightValid.stream().filter(k -> !darkValid.contains(k)).collect(Collectors.toList());
        List<String> onlyInDark = darkValid.stream().filter(k -> !lightValid.contains(k)).collect(Collectors.toList());
        if (!onlyInLight.isEmpty() || !onlyInDark.isEmpty()) {
            System.out.println(lightName + " vs " + darkName + ": recognized-key parity mismatch. Only in "
                    + lightName + ": " + onlyInLight + "; only in " + darkName + ": " + onlyInDark);
            return false;
        }
        System.out.println(lightName + " and " + darkName + ": " + lightValid.size()
                + " recognized keys match (raw files may still differ by keys unrecognized on either side)");
        return true;
    }

    // Check 5: every raw decimal literal must be a value Integer.parseInt() actually accepts, since
    // that's what Utilities.parseInt(CharSequence) - the runtime path MonetHelper's caller uses for a
    // raw decimal attheme value - delegates to internally (wrapped in its own try/catch that quietly
    // returns 0 on any failure, same fallback class as an unresolved token).
    private static boolean checkInt32RoundTrip(String assetName) throws IOException {
        Map<String, String> values = parseAttheme(ASSETS.resolve(assetName));
        List<String> bad = new ArrayList<>();
        for (Map.Entry<String, String> e : values.entrySet()) {
            String v = e.getValue();
            if (v.matches("-?\\d+") && !isInt32RoundTrip(v)) {
                bad.add(e.getKey() + "=" + v);
            }
        }
        if (!bad.isEmpty()) {
            System.out.println(assetName + " has " + bad.size() + " decimal value(s) Integer.parseInt() rejects "
                    + "(out of signed 32-bit range): " + bad);
            return false;
        }
        System.out.println(assetName + ": all raw decimal values fit a signed 32-bit int");
        return true;
    }

    // Utilities.parseInt(CharSequence) - the public overload Theme.getThemeFileValues() actually
    // calls for a raw decimal attheme value - extracts the leading "-?[0-9]+" run and hands it to
    // Integer.parseInt(), catching everything (including the NumberFormatException an out-of-range
    // value throws) and returning 0 on failure. It does NOT go through Utilities' own private
    // manual-accumulator parseInt(String) overload, which is dead code here - that one is never
    // called (its only call site is commented out) and doesn't match runtime behaviour: it wraps
    // silently past Integer.MIN_VALUE/MAX_VALUE with no exception instead of failing. Since
    // parseAttheme's decimal values here already match "-?\d+" in full (isResolvable's own decimal
    // check), the extraction step is a no-op, so calling Integer.parseInt() directly reproduces the
    // exact runtime outcome.
    private static boolean isInt32RoundTrip(String value) {
        try {
            Integer.parseInt(value);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
