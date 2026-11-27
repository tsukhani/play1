package play.db.jpa;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import jakarta.persistence.Basic;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Transient;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.config.Property;
import org.hibernate.annotations.LazyGroup;
import org.junit.jupiter.api.Test;
import play.Logger;

/**
 * PF-56: Play does not enhance entities with Hibernate's bytecode enhancer, so the attributes only enhancement makes
 * lazy load eagerly, which Hibernate does not say. These are the ones a start names in a WARN.
 */
public class LazyAttributesTest {

    @MappedSuperclass
    public static class Document {
        @Id public Long id;
        @Basic(fetch = FetchType.LAZY) public String body;
    }

    @Entity
    public static class Letter extends Document {
        public String title;
        @Basic public String subject;
        @Basic(fetch = FetchType.EAGER) public String summary;
        @Basic(fetch = FetchType.LAZY) @LazyGroup("scans") public byte[] scan;
        @LazyGroup("notes") public String notes;
        @Transient @Basic(fetch = FetchType.LAZY) public String draft;
        public static String TABLE = "letter";
    }

    @Entity
    public static class Memo extends Document {
        public String to;
    }

    /** Property access: the annotations sit on the getters. */
    @Entity
    public static class Account {
        private Long id;
        private String history;
        private boolean archived;
        private Profile profile;

        @Id public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        @Basic(fetch = FetchType.LAZY) public String getHistory() { return history; }
        public void setHistory(String history) { this.history = history; }
        @Basic(fetch = FetchType.LAZY) public boolean isArchived() { return archived; }
        public void setArchived(boolean archived) { this.archived = archived; }
        @OneToOne(mappedBy = "account", fetch = FetchType.LAZY) public Profile getProfile() { return profile; }
        public void setProfile(Profile profile) { this.profile = profile; }
    }

    @Entity
    public static class Profile {
        @Id public Long id;
        // The owning side: a proxy keeps it lazy, so it is fine.
        @OneToOne(fetch = FetchType.LAZY) public Account account;
        @ManyToOne(fetch = FetchType.LAZY) public Letter letter;
        @OneToMany(mappedBy = "profile", fetch = FetchType.LAZY) public List<Passport> passports;
        // The inverse side: Hibernate must query the owner to know whether there is one.
        @OneToOne(mappedBy = "profile", fetch = FetchType.LAZY) public Passport passport;
        // An eager inverse side is loaded eagerly as declared.
        @OneToOne(mappedBy = "owner") public Passport spare;
    }

    @Entity
    public static class Passport {
        @Id public Long id;
        @OneToOne public Profile profile;
        @OneToOne public Profile owner;
    }

    /** Not mapped: its fields are no attributes of the entity below. */
    public static class Helper {
        @Basic(fetch = FetchType.LAZY) public String scratch;
    }

    @Entity
    public static class Plain extends Helper {
        @Id public Long id;
        public String name;
    }

    @Test
    public void namesTheAttributesOnlyEnhancementWouldMakeLazy() {
        List<String> eager = LazyAttributes.loadedEagerly(List.of(Letter.class, Memo.class, Account.class, Profile.class));

        String prefix = LazyAttributesTest.class.getName() + "$";
        assertThat(eager).containsExactly(
                prefix + "Account.archived",
                prefix + "Account.history",
                prefix + "Account.profile",
                // declared once in the mapped superclass, which both entities share
                prefix + "Document.body",
                prefix + "Letter.notes",
                prefix + "Letter.scan",
                prefix + "Profile.passport");
    }

    @Test
    public void namesNothingForEntitiesWithoutSuch() {
        assertThat(LazyAttributes.loadedEagerly(List.of(Plain.class, Passport.class))).isEmpty();
        assertThat(LazyAttributes.loadedEagerly(List.of())).isEmpty();
    }

    @Test
    public void theWarningNamesTheUnitAndEveryAttribute() {
        assertThat(LazyAttributes.warning("default", List.of("models.Post.body")))
                .startsWith("JPA (default): this attribute is declared lazy but loaded eagerly,")
                .contains("bytecode enhancer: models.Post.body.")
                .contains("\"Lazy attributes\"");
        assertThat(LazyAttributes.warning("other", List.of("models.A.x", "models.B.y")))
                .startsWith("JPA (other): these attributes are declared lazy")
                .contains(": models.A.x, models.B.y.");
    }

    @Test
    public void aTransformerHandedToThePersistenceUnitIsReportedNotDroppedSilently() {
        PersistenceUnitInfoImpl unit = new PersistenceUnitInfoImpl("default", List.of(), List.of(), new Properties());
        List<String> warnings = new ArrayList<>();
        AbstractAppender appender = new AbstractAppender("PF-56", null, null, true, Property.EMPTY_ARRAY) {
            @Override
            public void append(LogEvent event) {
                if (event.getLevel().isMoreSpecificThan(Level.WARN)) {
                    warnings.add(event.getMessage().getFormattedMessage());
                }
            }
        };
        appender.start();
        org.apache.logging.log4j.Logger previousLog4j = Logger.log4j;
        boolean previousRecordCaller = Logger.recordCaller;
        boolean previousForceJuli = Logger.forceJuli;
        Level previousLevel = LogManager.getLogger("play").getLevel();
        org.apache.logging.log4j.core.Logger play = (org.apache.logging.log4j.core.Logger) LogManager.getLogger("play");
        try {
            Logger.log4j = play;
            Logger.recordCaller = false;
            Logger.forceJuli = false;
            Configurator.setLevel("play", Level.WARN);
            play.addAppender(appender);

            unit.addTransformer((loader, name, redefined, domain, bytes) -> bytes);

            assertThat(warnings).hasSize(1);
            assertThat(warnings.get(0)).startsWith("JPA (default): ").endsWith(" was not applied: Play does not enhance entities at load time");
        } finally {
            play.removeAppender(appender);
            Configurator.setLevel("play", previousLevel);
            Logger.log4j = previousLog4j;
            Logger.recordCaller = previousRecordCaller;
            Logger.forceJuli = previousForceJuli;
            appender.stop();
        }
    }
}
