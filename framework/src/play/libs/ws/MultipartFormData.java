package play.libs.ws;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;

import play.libs.MimeTypes;

/**
 * Builds a multipart/form-data body as raw bytes.
 * Used by WSAsync for file uploads and by FunctionalTest for multipart POST testing.
 */
public class MultipartFormData {

    private static final byte[] CRLF = "\r\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] DASH_DASH = "--".getBytes(StandardCharsets.US_ASCII);

    private final String boundary;
    private final ByteArrayOutputStream out;
    private boolean finished;

    public MultipartFormData() {
        this.boundary = UUID.randomUUID().toString();
        this.out = new ByteArrayOutputStream();
    }

    public void addField(String name, String value, Charset charset) {
        try {
            writeBoundary();
            writeHeader("Content-Disposition", "form-data; name=\"" + escape(name) + "\"");
            writeHeader("Content-Type", "text/plain; charset=" + charset.name());
            out.write(CRLF);
            out.write(value.getBytes(charset));
            out.write(CRLF);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void addFile(String paramName, File file) {
        addFile(paramName, file, MimeTypes.getMimeType(file.getName()), StandardCharsets.UTF_8);
    }

    public void addFile(String paramName, File file, String contentType, Charset charset) {
        try {
            byte[] fileBytes = Files.readAllBytes(file.toPath());
            writeBoundary();
            writeHeader("Content-Disposition",
                    "form-data; name=\"" + escape(paramName) + "\"; filename=\"" + escape(file.getName()) + "\"");
            writeHeader("Content-Type", contentType);
            writeHeader("Content-Transfer-Encoding", "binary");
            out.write(CRLF);
            out.write(fileBytes);
            out.write(CRLF);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void addBytes(String name, byte[] data, String contentType, Charset charset) {
        try {
            writeBoundary();
            writeHeader("Content-Disposition", "form-data; name=\"" + escape(name) + "\"");
            writeHeader("Content-Type", contentType + "; charset=" + charset.name());
            out.write(CRLF);
            out.write(data);
            out.write(CRLF);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public byte[] toByteArray() {
        if (!finished) {
            try {
                out.write(DASH_DASH);
                out.write(boundary.getBytes(StandardCharsets.US_ASCII));
                out.write(DASH_DASH);
                out.write(CRLF);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            finished = true;
        }
        return out.toByteArray();
    }

    public String getContentType() {
        return "multipart/form-data; boundary=" + boundary;
    }

    private void writeBoundary() throws IOException {
        out.write(DASH_DASH);
        out.write(boundary.getBytes(StandardCharsets.US_ASCII));
        out.write(CRLF);
    }

    private void writeHeader(String name, String value) throws IOException {
        out.write((name + ": " + value).getBytes(StandardCharsets.US_ASCII));
        out.write(CRLF);
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
