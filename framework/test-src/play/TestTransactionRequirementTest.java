package play;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Properties;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import play.exceptions.ConfigurationException;

/**
 * PF-231: the framework declares {@code play.test.transaction}, so a start refuses any value but
 * {@code ambient} and {@code none}, the two that {@code play.test.TestTransaction} tells apart.
 */
public class TestTransactionRequirementTest {

    private Properties savedConfiguration;

    @BeforeEach
    public void setUp() {
        savedConfiguration = Play.configuration;
        Play.configuration = new Properties();
        ConfigRequirements.reset();
    }

    @AfterEach
    public void tearDown() {
        Play.configuration = savedConfiguration;
        ConfigRequirements.reset();
    }

    @Test
    public void acceptsAmbientAndNoneInAnyLetterCaseOrNoValue() {
        assertThatCode(ConfigRequirements::check).as("not set").doesNotThrowAnyException();
        for (String value : new String[] {"ambient", "none", "AMBIENT", " None ", ""}) {
            Play.configuration.setProperty("play.test.transaction", value);
            assertThatCode(ConfigRequirements::check).as("[%s]", value).doesNotThrowAnyException();
        }
    }

    @Test
    public void refusesAnyOtherValue() {
        Play.configuration.setProperty("play.test.transaction", "off");
        assertThatThrownBy(ConfigRequirements::check)
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("play.test.transaction (declared by Play framework) is \"off\", which is not an"
                        + " allowed value. Set it to one of: ambient, none.");
    }
}
