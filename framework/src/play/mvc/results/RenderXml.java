package play.mvc.results;

import org.w3c.dom.Document;

import play.exceptions.UnexpectedException;
import play.libs.XML;
import play.mvc.Http.Request;
import play.mvc.Http.Response;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;

/**
 * 200 OK with a text/xml
 */
public class RenderXml extends Result {

    // XmlMapper is thread-safe once configured and non-trivial to construct.
    // Jackson's documented pattern is one shared instance.
    private static final XmlMapper XML_MAPPER = new XmlMapper();

    private final String xml;

    public RenderXml(CharSequence xml) {
        this.xml = xml.toString();
    }

    public RenderXml(Document document) {
        this.xml = XML.serialize(document);
    }

    public RenderXml(Object o) {
        try {
            this.xml = XML_MAPPER.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new UnexpectedException(e);
        }
    }

    @Override
    public void apply(Request request, Response response) {
        try {
            setContentTypeIfNotSet(response, "text/xml");
            response.out.write(xml.getBytes(getEncoding()));
        } catch(Exception e) {
            throw new UnexpectedException(e);
        }
    }

    public String getXml() {
        return xml;
    }
}
