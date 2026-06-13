package play.libs;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.FileImageOutputStream;

/**
 * Images utils
 */
public class Images {

    /**
     * Resize an image
     *
     * @param originalImage
     *            The image file
     * @param to
     *            The destination file
     * @param w
     *            The new width (or -1 to proportionally resize)
     * @param h
     *            The new height (or -1 to proportionally resize)
     */
    public static void resize(File originalImage, File to, int w, int h) {
        resize(originalImage, to, w, h, false);
    }

    /**
     * Resize an image
     *
     * @param originalImage
     *            The image file
     * @param to
     *            The destination file
     * @param w
     *            The new width (or -1 to proportionally resize) or the maxWidth if keepRatio is true
     * @param h
     *            The new height (or -1 to proportionally resize) or the maxHeight if keepRatio is true
     * @param keepRatio
     *            if true, resize will keep the original image ratio and use w and h as max dimensions
     */
    public static void resize(File originalImage, File to, int w, int h, boolean keepRatio) {
        try {
            BufferedImage source = ImageIO.read(originalImage);
            int owidth = source.getWidth();
            int oheight = source.getHeight();
            double ratio = (double) owidth / oheight;

            int maxWidth = w;
            int maxHeight = h;

            if (w < 0 && h < 0) {
                w = owidth;
                h = oheight;
            }
            if (w < 0 && h > 0) {
                w = (int) (h * ratio);
            }
            if (w > 0 && h < 0) {
                h = (int) (w / ratio);
            }

            if (keepRatio) {
                h = (int) (w / ratio);
                if (h > maxHeight) {
                    h = maxHeight;
                    w = (int) (h * ratio);
                }
                if (w > maxWidth) {
                    w = maxWidth;
                    h = (int) (w / ratio);
                }
            }

            String mimeType = "image/jpeg";
            if (to.getName().endsWith(".png")) {
                mimeType = "image/png";
            }
            if (to.getName().endsWith(".gif")) {
                mimeType = "image/gif";
            }

            // out
            BufferedImage dest = null;
            Graphics graphics = null;
            if (source.getColorModel().hasAlpha()) {
                dest = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
                graphics = dest.getGraphics();
            } else {
                dest = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
                // Create a white background if not transparency define
                graphics = dest.getGraphics();
                graphics.setColor(Color.BLUE);
                graphics.fillRect(0, 0, w, h);
            }
            Image srcSized = source.getScaledInstance(w, h, Image.SCALE_SMOOTH);

            graphics.drawImage(srcSized, 0, 0, null);

            ImageWriter writer = ImageIO.getImageWritersByMIMEType(mimeType).next();
            ImageWriteParam params = writer.getDefaultWriteParam();

            try (FileImageOutputStream toFs = new FileImageOutputStream(to)) {
                writer.setOutput(toFs);
                IIOImage image = new IIOImage(dest, null, null);
                writer.write(null, image, params);
                toFs.flush();
            }
            writer.dispose();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

    }

    /**
     * Crop an image
     *
     * @param originalImage
     *            The image file
     * @param to
     *            The destination file
     * @param x1
     *            The new x origin
     * @param y1
     *            The new y origin
     * @param x2
     *            The new x end
     * @param y2
     *            The new y end
     */
    public static void crop(File originalImage, File to, int x1, int y1, int x2, int y2) {
        try {
            BufferedImage source = ImageIO.read(originalImage);

            String mimeType = "image/jpeg";
            if (to.getName().endsWith(".png")) {
                mimeType = "image/png";
            }
            if (to.getName().endsWith(".gif")) {
                mimeType = "image/gif";
            }
            int width = x2 - x1;
            int height = y2 - y1;

            // out
            BufferedImage dest = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            Image croppedImage = source.getSubimage(x1, y1, width, height);
            Graphics graphics = dest.getGraphics();
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, width, height);
            graphics.drawImage(croppedImage, 0, 0, null);
            ImageWriter writer = ImageIO.getImageWritersByMIMEType(mimeType).next();
            ImageWriteParam params = writer.getDefaultWriteParam();

            try (FileImageOutputStream toFs = new FileImageOutputStream(to)) {
                writer.setOutput(toFs);
                IIOImage image = new IIOImage(dest, null, null);
                writer.write(null, image, params);
                toFs.flush();
            }
            writer.dispose();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

    }

    /**
     * Encode an image to base64 using a data: URI
     *
     * @param image
     *            The image file
     * @return The base64 encoded value
     * @throws java.io.IOException
     *             Thrown if the encoding encounters any problems.
     */
    public static String toBase64(File image) throws IOException {
        return "data:" + MimeTypes.getMimeType(image.getName()) + ";base64," + Codec.encodeBASE64(IO.readContent(image));
    }
}
