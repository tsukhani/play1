package play;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import play.exceptions.ConfigurationException;

/**
 * Configuration keys that are declared up front and checked together when the application
 * starts (PF-179).
 *
 * <p>
 * Configuration values are read untyped at the point of use, so a missing key or a
 * mistyped value otherwise surfaces at first use — or never, when the reader quietly falls
 * back to a default. A plugin declares what it needs from
 * {@link PlayPlugin#onConfigurationRead()}:
 *
 * <pre>
 * public void onConfigurationRead() {
 *     ConfigRequirements.require(BillingPlugin.class, "billing.apiKey");
 *     ConfigRequirements.requireIn(BillingPlugin.class, "billing.mode", "live", "sandbox");
 * }
 * </pre>
 *
 * <p>
 * {@link Play#start()} then refuses to start while any declaration is violated and names
 * all of them in one {@link ConfigurationException}. Keys that nobody declares are never
 * looked at. Declaring belongs in {@code onConfigurationRead()} because the registry is
 * emptied every time the configuration is read: the hook runs again after each read, so a
 * DEV restart or a plugin reload starts from a clean slate.
 */
public final class ConfigRequirements {

    /** Named as the owner of the keys the framework declares for itself. */
    private static final String FRAMEWORK = "Play framework";

    /** A placeholder as Play.readOneConfigurationFile resolves it; group 1 is the variable. */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}:]+)[^}]*}");

    private static final List<Declaration> declarations = new ArrayList<>();

    /** One call to require or requireIn; {@code allowed} is null for require. */
    private record Declaration(Object owner, String key, List<String> allowed) {
    }

    private ConfigRequirements() {
    }

    /**
     * Declare that a key must have a value: present, not empty, and with no
     * <code>${...}</code> placeholder left unresolved.
     *
     * @param owner
     *            The class that needs the key, named in the error message. When it is a
     *            plugin that has been disabled, the declaration is ignored.
     * @param key
     *            The configuration key, without any <code>%id.</code> prefix
     */
    public static void require(Class<?> owner, String key) {
        declare(owner, key, null);
    }

    /**
     * Declare that a key must have a value: present, not empty, and with no
     * <code>${...}</code> placeholder left unresolved.
     *
     * @param owner
     *            A label for whatever needs the key, named in the error message
     * @param key
     *            The configuration key, without any <code>%id.</code> prefix
     */
    public static void require(String owner, String key) {
        declare(owner, key, null);
    }

    /**
     * Declare the values a key may take, compared without regard to letter case or
     * surrounding whitespace. This constrains a value and does not demand one: a key that is
     * absent or empty passes. Declare it with {@link #require(Class, String)} as well to make
     * it mandatory.
     *
     * @param owner
     *            The class that reads the key, named in the error message. When it is a
     *            plugin that has been disabled, the declaration is ignored.
     * @param key
     *            The configuration key, without any <code>%id.</code> prefix
     * @param allowed
     *            The accepted values
     */
    public static void requireIn(Class<?> owner, String key, String... allowed) {
        declare(owner, key, List.of(allowed));
    }

    /**
     * Declare the values a key may take, compared without regard to letter case or
     * surrounding whitespace. This constrains a value and does not demand one: a key that is
     * absent or empty passes. Declare it with {@link #require(String, String)} as well to make
     * it mandatory.
     *
     * @param owner
     *            A label for whatever reads the key, named in the error message
     * @param key
     *            The configuration key, without any <code>%id.</code> prefix
     * @param allowed
     *            The accepted values
     */
    public static void requireIn(String owner, String key, String... allowed) {
        declare(owner, key, List.of(allowed));
    }

    private static synchronized void declare(Object owner, String key, List<String> allowed) {
        declarations.add(new Declaration(owner, key, allowed));
    }

    /**
     * Forget every declaration, then declare the framework's own keys again. Runs on each
     * configuration read, ahead of the plugins' {@code onConfigurationRead()}, so what
     * {@link #check()} sees is what the current plugins asked of the current configuration.
     */
    static synchronized void reset() {
        declarations.clear();
        // Logger selects the JSON layout for json and the text one for anything else, so a
        // mistyped value used to mean text without a word.
        requireIn(FRAMEWORK, "application.log.format", "text", "json");
        // application.session.sameSite is deliberately not declared. Its value only takes
        // effect as Lax, Strict or None in that exact letter case, and anything else is
        // dropped from the cookie rather than refused; a case-blind allowed set would either
        // approve values that are ignored or reject configurations that start today.
    }

    /**
     * Fail if any declared key violates its declarations.
     *
     * @throws ConfigurationException
     *             One exception listing every violation with its key, who declared it and
     *             how to fix it
     */
    static synchronized void check() {
        // One entry per key, however often and by whomever it was declared.
        Map<String, List<Declaration>> byKey = new LinkedHashMap<>();
        for (Declaration declaration : declarations) {
            if (!ofDisabledPlugin(declaration)) {
                byKey.computeIfAbsent(declaration.key, key -> new ArrayList<>()).add(declaration);
            }
        }
        List<String> violations = new ArrayList<>();
        byKey.forEach((key, declared) -> {
            String violation = violation(key, declared, Play.configuration.getProperty(key));
            if (violation != null) {
                violations.add(violation);
            }
        });
        if (violations.isEmpty()) {
            return;
        }
        // Numbered, because the log prints an exception's description on one line and the
        // DEV error page in one paragraph.
        StringBuilder message = new StringBuilder("Invalid configuration (").append(violations.size())
                .append(violations.size() == 1 ? " problem):" : " problems):");
        for (int i = 0; i < violations.size(); i++) {
            message.append("\n  ").append(i + 1).append(". ").append(violations.get(i));
        }
        throw new ConfigurationException(message.toString());
    }

    /**
     * A plugin named in {@code plugins.disable} is switched off by
     * ConfigurablePluginDisablingPlugin in the same broadcast in which it has just declared,
     * so its declarations are still in the registry when the check runs. A disabled plugin
     * reads none of its keys and must not hold up the start over them.
     */
    private static boolean ofDisabledPlugin(Declaration declaration) {
        if (declaration.owner instanceof Class<?> type && PlayPlugin.class.isAssignableFrom(type)) {
            // Every loaded plugin of the class, not the first: a subclass that replaces a
            // disabled plugin declares under the same class through the hook it inherits.
            List<PlayPlugin> owners = Play.pluginCollection.getAllPlugins().stream().filter(type::isInstance).toList();
            return !owners.isEmpty() && owners.stream().noneMatch(Play.pluginCollection::isEnabled);
        }
        return false;
    }

    /** @return what is wrong with the key's value and how to fix it, or null if nothing is */
    private static String violation(String key, List<Declaration> declared, String configured) {
        Set<String> owners = new LinkedHashSet<>();
        boolean required = false;
        List<String> allowed = null;
        for (Declaration declaration : declared) {
            owners.add(declaration.owner instanceof Class<?> type ? type.getName() : declaration.owner.toString());
            if (declaration.allowed == null) {
                required = true;
            } else if (allowed == null) {
                allowed = new ArrayList<>(declaration.allowed);
            } else {
                // Declared with more than one set: the value has to satisfy each of them.
                allowed.removeIf(candidate -> !containsIgnoreCase(declaration.allowed, candidate));
            }
        }
        String value = configured == null ? "" : configured.trim();
        String subject = key + " (declared by " + String.join(", ", owners) + ") ";
        String oneOf = allowed == null ? null : String.join(", ", allowed);

        // An unset variable with no default leaves its placeholder in the value, which
        // Play.start() takes for no value in application.secret too. Only the variable is
        // named: the text around the placeholder may be a credential.
        Set<String> undefined = new LinkedHashSet<>();
        Matcher placeholder = PLACEHOLDER.matcher(value);
        while (placeholder.find()) {
            undefined.add(placeholder.group(1));
        }
        if (!undefined.isEmpty()) {
            return subject + "is missing: nothing defines ${" + String.join("}, ${", undefined) + "}. Define "
                    + String.join(", ", undefined) + " in the environment, in certs/.env or as a -D system "
                    + "property, or give the placeholder a default: ${" + undefined.iterator().next() + ":value}."
                    + (oneOf == null ? "" : " Allowed values: " + oneOf + ".");
        }
        if (value.isEmpty()) {
            return !required ? null : subject + "is missing. Set it in conf/application.conf"
                    + (oneOf == null ? "." : " to one of: " + oneOf + ".");
        }
        if (allowed != null && !containsIgnoreCase(allowed, value)) {
            return subject + "is \"" + value + "\", which is not an allowed value. Set it to one of: " + oneOf + ".";
        }
        return null;
    }

    private static boolean containsIgnoreCase(List<String> values, String value) {
        return values.stream().anyMatch(value::equalsIgnoreCase);
    }
}
