package play.utils;

import play.Play;

/**
 * Reads virtual thread configuration from Play's configuration.
 *
 * Configuration properties:
 * <ul>
 *   <li>{@code play.threads.virtual} — global enable/disable (default: false)</li>
 *   <li>{@code play.threads.virtual.invoker} — override for request invocation (inherits from global)</li>
 *   <li>{@code play.threads.virtual.jobs} — override for background jobs (inherits from global)</li>
 *   <li>{@code play.threads.virtual.mail} — override for mail delivery (inherits from global)</li>
 * </ul>
 */
public class VirtualThreadConfig {

    private VirtualThreadConfig() {
    }

    /**
     * Returns true if virtual threads are globally enabled.
     * <p>
     * Audit M16: returns false (the documented default) when {@code Play.configuration}
     * has not yet been populated. This protects test paths and plugins that may
     * lazily reach VT config before {@code Play.init()} has run — e.g. a unit
     * test that triggers {@code Mail.send()} without booting the full framework.
     * Without the guard, every such call NPE'd inside {@code Properties.getProperty}.
     */
    public static boolean isGlobalEnabled() {
        if (Play.configuration == null) return false;
        return Boolean.parseBoolean(Play.configuration.getProperty("play.threads.virtual", "false"));
    }

    /**
     * Returns true if virtual threads are enabled for the invoker subsystem.
     * Falls back to the global setting if no per-subsystem override is set.
     */
    public static boolean isInvokerEnabled() {
        return isSubsystemEnabled("invoker");
    }

    /**
     * Returns true if virtual threads are enabled for the jobs subsystem.
     * Falls back to the global setting if no per-subsystem override is set.
     */
    public static boolean isJobsEnabled() {
        return isSubsystemEnabled("jobs");
    }

    /**
     * Returns true if virtual threads are enabled for the mail subsystem.
     * Falls back to the global setting if no per-subsystem override is set.
     */
    public static boolean isMailEnabled() {
        return isSubsystemEnabled("mail");
    }

    private static boolean isSubsystemEnabled(String subsystem) {
        // Audit M16: same null guard as isGlobalEnabled — protects test paths and plugins
        // that hit VT config before Play.init() has populated configuration.
        if (Play.configuration == null) return false;
        // Audit M2: treat blank/whitespace-only values as "not set" so they fall through
        // to the global setting rather than being parsed as "false". A line like
        // `play.threads.virtual.invoker=` in application.conf reads back as an empty
        // string, not null; without isBlank() that empty value would silently override
        // a `play.threads.virtual=true` global, surprising operators who expect blank
        // to mean "inherit".
        String override = Play.configuration.getProperty("play.threads.virtual." + subsystem);
        if (override != null && !override.isBlank()) {
            return Boolean.parseBoolean(override.trim());
        }
        return isGlobalEnabled();
    }
}
