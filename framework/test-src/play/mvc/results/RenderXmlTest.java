package play.mvc.results;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class RenderXmlTest {

    public record Pet(String name, int age) {}

    @Test
    public void serializesPojoViaJacksonXml() {
        // PF-26 regression: renderXml(Object) is now backed by Jackson's
        // XmlMapper, not XStream. A plain POJO must round-trip through the
        // serializer with field elements visible in the output.
        RenderXml result = new RenderXml(new Pet("Rex", 5));
        String xml = result.getXml();

        assertThat(xml).contains("<name>Rex</name>");
        assertThat(xml).contains("<age>5</age>");
    }
}
