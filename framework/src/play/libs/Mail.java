package play.libs;

import java.util.Date;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import jakarta.mail.Authenticator;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.mail2.jakarta.Email;
import org.apache.commons.mail2.core.EmailException;

import play.Logger;
import play.Play;
import play.exceptions.MailException;
import play.libs.mail.AbstractMailSystemFactory;
import play.libs.mail.MailSystem;
import play.libs.mail.test.LegacyMockMailSystem;
import play.utils.Utils.Maps;
import play.utils.VirtualThreadConfig;
import play.utils.VirtualThreadFactory;

/**
 * Mail utils
 */
public class Mail {

    private static class StaticMailSystemFactory extends AbstractMailSystemFactory {

        private final MailSystem mailSystem;

        private StaticMailSystemFactory(MailSystem mailSystem) {
            this.mailSystem = mailSystem;
        }

        @Override
        public MailSystem currentMailSystem() {
            return mailSystem;
        }

    }

    public static Session session;
    public static boolean asynchronousSend = true;
    protected static AbstractMailSystemFactory mailSystemFactory = AbstractMailSystemFactory.DEFAULT;

    /**
     * Send an email
     * 
     * @param email
     *            An Email message
     * @return true if email successfully send
     */
    public static Future<Boolean> send(Email email) {
        try {
            email = buildMessage(email);
            return currentMailSystem().sendMessage(email);
        } catch (EmailException ex) {
            throw new MailException("Cannot send email", ex);
        }
    }

    // Helper method for better readability
    protected static MailSystem currentMailSystem() {
        return mailSystemFactory.currentMailSystem();
    }

    /**
     * Through this method you can substitute the current MailSystem. This is especially helpful for testing purposes
     * like using mock libraries.
     *
     * @author Andreas Simon &lt;a.simon@quagilis.de&gt;
     * @param mailSystem
     *            The mailSystem to use
     * @see MailSystem
     */
    public static void useMailSystem(MailSystem mailSystem) {
        mailSystemFactory = new StaticMailSystemFactory(mailSystem);
    }

    public static void resetMailSystem() {
        mailSystemFactory = AbstractMailSystemFactory.DEFAULT;
    }

    public static Email buildMessage(Email email) throws EmailException {
        String from = Play.configuration.getProperty("mail.smtp.from");
        if (email.getFromAddress() == null && !StringUtils.isEmpty(from)) {
            email.setFrom(from);
        } else if (email.getFromAddress() == null) {
            throw new MailException("Please define a 'from' email address", new NullPointerException());
        }
        if ((email.getToAddresses() == null || email.getToAddresses().isEmpty())
                && (email.getCcAddresses() == null || email.getCcAddresses().isEmpty())
                && (email.getBccAddresses() == null || email.getBccAddresses().isEmpty())) {
            throw new MailException("Please define a recipient email address", new NullPointerException());
        }
        if (email.getSubject() == null) {
            throw new MailException("Please define a subject", new NullPointerException());
        }
        if (email.getReplyToAddresses() == null || email.getReplyToAddresses().isEmpty()) {
            email.addReplyTo(email.getFromAddress().getAddress());
        }

        return email;
    }

    public static Session getSession() {
        if (session == null) {
            Properties props = new Properties();
            // Put a bogus value even if we are on dev mode, otherwise JavaMail will complain
            props.put("mail.smtp.host", Play.configuration.getProperty("mail.smtp.host", "localhost"));

            String channelEncryption;
            if (Play.configuration.containsKey("mail.smtp.protocol")
                    && "smtps".equals(Play.configuration.getProperty("mail.smtp.protocol", "smtp"))) {
                // Backward compatibility before stable5
                channelEncryption = "starttls";
            } else {
                channelEncryption = Play.configuration.getProperty("mail.smtp.channel", "clear");
            }

            if ("clear".equals(channelEncryption)) {
                props.put("mail.smtp.port", "25");
            } else if ("ssl".equals(channelEncryption)) {
                // port 465 + setup yes ssl socket factory (won't verify that the server certificate is signed with a
                // root ca.)
                props.put("mail.smtp.port", "465");
                props.put("mail.smtp.socketFactory.port", "465");
                props.put("mail.smtp.socketFactory.class", "play.utils.YesSSLSocketFactory");
                props.put("mail.smtp.socketFactory.fallback", "false");
            } else if ("starttls".equals(channelEncryption)) {
                // port 25 + enable starttls + ssl socket factory
                props.put("mail.smtp.port", "25");
                props.put("mail.smtp.starttls.enable", "true");
                // can't install our socket factory. will work only with server that has a signed certificate
                // story to be continued in javamail 1.4.2 : https://glassfish.dev.java.net/issues/show_bug.cgi?id=5189
            }

            // Inject additional mail.* settings declared in Play! configuration
            Map<Object, Object> additionalSettings = Maps.filterMap(Play.configuration, "^mail\\..*");
            if (!additionalSettings.isEmpty()) {
                // Remove "password" fields
                additionalSettings.remove("mail.smtp.pass");
                additionalSettings.remove("mail.smtp.password");
                props.putAll(additionalSettings);
            }

            String user = Play.configuration.getProperty("mail.smtp.user");
            String password = Play.configuration.getProperty("mail.smtp.pass");
            if (password == null) {
                // Fallback to old convention
                password = Play.configuration.getProperty("mail.smtp.password");
            }
            String authenticator = Play.configuration.getProperty("mail.smtp.authenticator");
            session = null;

            if (authenticator != null) {
                props.put("mail.smtp.auth", "true");
                try {
                    session = Session.getInstance(props, (Authenticator) Play.classloader.loadClass(authenticator).getDeclaredConstructor().newInstance());
                } catch (Exception e) {
                    Logger.error(e, "Cannot instantiate custom SMTP authenticator (%s)", authenticator);
                }
            }

            if (session == null) {
                if (user != null && password != null) {
                    props.put("mail.smtp.auth", "true");
                    session = Session.getInstance(props, new SMTPAuthenticator(user, password));
                } else {
                    props.remove("mail.smtp.auth");
                    session = Session.getInstance(props);
                }
            }

            if (Boolean.parseBoolean(Play.configuration.getProperty("mail.debug", "false"))) {
                session.setDebug(true);
            }
        }
        return session;
    }

    /**
     * Send a JavaMail message
     *
     * @param msg
     *            An Email message
     * @return true if email successfully send
     */
    public static Future<Boolean> sendMessage(final Email msg) {
        if (asynchronousSend) {
            return getExecutor().submit(() -> {
                // Gate the actual SMTP send through a global semaphore. Without this,
                // virtual-thread mode (newVirtualThreadPerTaskExecutor) lets callers
                // create unbounded concurrent sends — overwhelming the SMTP server,
                // tripping rate limits, and amplifying connection storms. The cap
                // applies regardless of executor type for consistent semantics.
                //
                // Interruptible acquire so Future.cancel(true) and resetExecutor() can
                // unblock waiters cleanly — without this, a saturated gate would let
                // shutdown leak still-pending sends and run them on the next startup.
                Semaphore gate = getMailGate();
                try {
                    gate.acquire();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return false;
                }
                try {
                    msg.setSentDate(new Date());
                    msg.send();
                    return true;
                } catch (Throwable e) {
                    MailException me = new MailException("Error while sending email", e);
                    Logger.error(me, "The email has not been sent");
                    return false;
                } finally {
                    gate.release();
                }
            });
        } else {
            final StringBuilder result = new StringBuilder();
            try {
                msg.setSentDate(new Date());
                msg.send();
            } catch (Throwable e) {
                MailException me = new MailException("Error while sending email", e);
                Logger.error(me, "The email has not been sent");
                result.append("oops");
            }
            return new Future<Boolean>() {
                @Override
                public boolean cancel(boolean mayInterruptIfRunning) {
                    return false;
                }

                @Override
                public boolean isCancelled() {
                    return false;
                }

                @Override
                public boolean isDone() {
                    return true;
                }

                @Override
                public Boolean get() {
                    return result.length() == 0;
                }

                @Override
                public Boolean get(long timeout, TimeUnit unit) {
                    return result.length() == 0;
                }
            };
        }
    }

    static volatile ExecutorService executor;
    static volatile Semaphore mailGate;

    /**
     * Returns the back-pressure semaphore that gates concurrent SMTP sends. Capacity
     * is read once from {@code play.mail.maxConcurrent} (default 32) and cached; call
     * {@link #resetExecutor()} to pick up a config change. The cap applies in both
     * platform-thread and virtual-thread executor modes.
     */
    static Semaphore getMailGate() {
        Semaphore g = mailGate;
        if (g == null) {
            synchronized (Mail.class) {
                g = mailGate;
                if (g == null) {
                    // Audit H4: defensively handle null configuration (test path) and
                    // malformed values (`play.mail.maxConcurrent=foo`). Either failure
                    // mode previously bubbled up as NPE / NumberFormatException out of
                    // a mail send, killing the request. Default to 32 in both cases.
                    int max = 32;
                    String raw = (Play.configuration == null)
                            ? null
                            : Play.configuration.getProperty("play.mail.maxConcurrent", "32");
                    if (raw != null) {
                        try {
                            max = Integer.parseInt(raw.trim());
                        } catch (NumberFormatException nfe) {
                            Logger.warn("Invalid play.mail.maxConcurrent=%s; using default 32", raw);
                            max = 32;
                        }
                    }
                    g = new Semaphore(max);
                    mailGate = g;
                }
            }
        }
        return g;
    }

    static ExecutorService getExecutor() {
        if (executor == null) {
            synchronized (Mail.class) {
                if (executor == null) {
                    // VTs named "mail-vthread-N" so they're distinguishable from the JDK
                    // default "virtual-N" in thread dumps. Matches Invoker ("play-vthread-N")
                    // and JobsPlugin ("jobs-vthread-N").
                    executor = Executors.newThreadPerTaskExecutor(new VirtualThreadFactory("mail"));
                    Logger.info("Mail using virtual threads");
                }
            }
        }
        return executor;
    }

    /**
     * Reset the executor and the back-pressure semaphore. Called during application
     * restart so configuration changes (mode, concurrency cap) are picked up.
     */
    public static void resetExecutor() {
        synchronized (Mail.class) {
            if (executor != null) {
                executor.shutdownNow();
                executor = null;
            }
            mailGate = null;
        }
    }

    public static class SMTPAuthenticator extends Authenticator {

        private final String user;
        private final String password;

        public SMTPAuthenticator(String user, String password) {
            this.user = user;
            this.password = password;
        }

        @Override
        protected PasswordAuthentication getPasswordAuthentication() {
            return new PasswordAuthentication(user, password);
        }
    }

    /**
     * Just kept for compatibility reasons, use test double substitution mechanism instead.
     *
     * @see Mail#useMailSystem(MailSystem)
     * @author Andreas Simon &lt;a.simon@quagilis.de&gt;
     */
    public static LegacyMockMailSystem Mock = new LegacyMockMailSystem();
}
