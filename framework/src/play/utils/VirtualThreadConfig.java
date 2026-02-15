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
     */
    public static boolean isGlobalEnabled() {
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
        String override = Play.configuration.getProperty("play.threads.virtual." + subsystem);
        if (override != null) {
            return Boolean.parseBoolean(override);
        }
        return isGlobalEnabled();
    }
}
