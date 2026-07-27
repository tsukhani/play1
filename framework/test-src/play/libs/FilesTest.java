package play.libs;



import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import play.utils.OS;

import static org.junit.jupiter.api.Assertions.*;

/**
 * @author Marek Piechut
 */
public class FilesTest {

    @Test
    public void testSanitizeFileName() throws Exception {
        // File names to test are on odd indexes and expected results are on even indexes, ex:
        // test_file_name, expected_file_name
        String[] FILE_NAMES = { null, null, "", "", "a", "a", "test.file", "test.file", "validfilename-,^&'@{}[],$=!-#()%.+~_.&&&",
                "validfilename-,^&'@{}[],$=!-#()%.+~_.&&&", "invalid/file", "invalid_file", "invalid\\file", "invalid_file",
                "invalid:*?\\<>|/file", "invalid________file", };

        for (int i = 0; i < FILE_NAMES.length; i += 2) {
            String actual = Files.sanitizeFileName(FILE_NAMES[i]);
            String expected = FILE_NAMES[i + 1];

            assertEquals(expected, actual, "String was not sanitized properly");
        }
    }

    @Test
    public void testFileEqualsOnWindows() {
        if (OS.isWindows()) {
            File a = null;
            File b = null;

            a = new File("C:\\temp\\TEST.TXT");
            b = new File("C:\\temp\\TEST.TXT");
            assertTrue(Files.isSameFile(a, b), String.format("Error comparing %s and %s", a.getPath(), b.getPath()));

            a = new File("C:\\temp\\TEST.TXT");
            b = new File("C:\\temp\\TEST.TXT");
            assertTrue(Files.isSameFile(a, b), String.format("Error comparing %s and %s", a.getPath(), b.getPath()) );

            a = new File("C:\\temp\\TEST.TXT");
            b = new File("C:\\temp\\test.txt");
            assertTrue( Files.isSameFile(a, b), String.format("Error comparing %s and %s", a.getPath(), b.getPath()));

            a = new File("C:\\temp\\TEST.TXT");
            b = new File("C:\\temp\\.\\test.txt");
            assertTrue( Files.isSameFile(a, b), String.format("Error comparing %s and %s", a.getPath(), b.getPath()));

            a = new File("C:\\temp\\..\\TEMP\\TEST.TXT");
            b = new File("C:\\temp\\.\\test.txt");
            assertTrue(Files.isSameFile(a, b), String.format("Error comparing %s and %s", a.getPath(), b.getPath()));
        }
    }

    @Test
    public void testFileEquals() {
        File a = null;
        File b = null;

        a = new File("temp\\TEST.TXT");
        b = new File("temp\\TEST.TXT");
        assertTrue(Files.isSameFile(a, b), String.format("Error comparing %s and %s", a.getPath(), b.getPath()));

        a = new File("\\temp\\TEST.TXT");
        b = new File("\\temp\\TEST.TXT");
        assertTrue(Files.isSameFile(a, b), String.format("Error comparing %s and %s", a.getPath(), b.getPath()));

        a = new File("\\temp\\TEST.TXT");
        b = new File("\\temp\\test.txt");
        if (OS.isWindows()) {
            assertTrue(Files.isSameFile(a, b), String.format("Error comparing %s and %s", a.getPath(), b.getPath()));
        } else {
            assertFalse(Files.isSameFile(a, b), String.format("Error comparing %s and %s", a.getPath(), b.getPath()));
        }

        a = new File("/temp/TEST.TXT");
        b = new File("/temp/TEST.TXT");
        assertTrue(Files.isSameFile(a, b), String.format("Error comparing %s and %s", a.getPath(), b.getPath()));

        a = new File("/temp/TEST.TXT");
        b = new File("/temp/test.txt");
        if (OS.isWindows()) {
            assertTrue(Files.isSameFile(a, b), String.format("Error comparing %s and %s", a.getPath(), b.getPath()));
        } else {
            assertFalse(Files.isSameFile(a, b), String.format("Error comparing %s and %s", a.getPath(), b.getPath()));
        }
    }

    @Test
    public void testFileEqualsWithParentCurrentFolder() {
        File a = null;
        File b = null;

        a = new File("\\temp\\test.txt");
        b = new File("\\temp\\.\\test.txt");
        if (OS.isWindows()) {
            assertTrue( Files.isSameFile(a, b),String.format("Error comparing %s and %s", a.getPath(), b.getPath()));
        } else {
            assertFalse( Files.isSameFile(a, b),String.format("Error comparing %s and %s", a.getPath(), b.getPath()));
        }

        a = new File("/temp/../temp/test.txt");
        b = new File("/temp/test.txt");
        assertTrue(Files.isSameFile(a, b), String.format("Error comparing %s and %s", a.getPath(), b.getPath()));

        a = new File("/temp/test.txt");
        b = new File("/temp/./test.txt");
        assertTrue(Files.isSameFile(a, b), String.format("Error comparing %s and %s", a.getPath(), b.getPath()));

        a = new File("/temp/../temp/test.txt");
        b = new File("/temp/./test.txt");
        assertTrue(Files.isSameFile(a, b), String.format("Error comparing %s and %s", a.getPath(), b.getPath()));
    }

    /**
     * Zip-slip via a sibling directory sharing the output directory's name prefix. The
     * containment guard used to be a String startsWith on the canonical path, which accepts
     * "<out>EVIL" as being "inside" "<out>". Path.startsWith compares whole name elements,
     * so the entry is now rejected.
     */
    @Test
    public void unzipRejectsEntryEscapingIntoAPrefixSharingSibling(@TempDir Path tmp) throws Exception {
        File out = tmp.resolve("out").toFile();
        assertTrue(out.mkdir());
        File zip = tmp.resolve("evil.zip").toFile();
        // "../outEVIL/pwned.txt" canonicalises to a sibling of `out` whose path string
        // starts with out's path string — the exact bypass the old check allowed.
        writeZip(zip, "../outEVIL/pwned.txt", false);

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> Files.unzip(zip, out));
        assertInstanceOf(IOException.class, thrown.getCause(), "expected the containment guard to trip");
        assertFalse(tmp.resolve("outEVIL/pwned.txt").toFile().exists(), "file escaped the output directory");
    }

    /**
     * The directory branch of unzip previously called mkdir() with no containment check at
     * all, so a traversing directory entry could create directories outside the target.
     */
    @Test
    public void unzipRejectsTraversingDirectoryEntry(@TempDir Path tmp) throws Exception {
        File out = tmp.resolve("out").toFile();
        assertTrue(out.mkdir());
        File zip = tmp.resolve("evil-dir.zip").toFile();
        writeZip(zip, "../escaped-dir/", true);

        assertThrows(RuntimeException.class, () -> Files.unzip(zip, out));
        assertFalse(tmp.resolve("escaped-dir").toFile().exists(), "directory escaped the output directory");
    }

    /** A well-formed archive still extracts. */
    @Test
    public void unzipExtractsLegitimateEntries(@TempDir Path tmp) throws Exception {
        File out = tmp.resolve("out").toFile();
        assertTrue(out.mkdir());
        File zip = tmp.resolve("good.zip").toFile();
        writeZip(zip, "nested/hello.txt", false);

        Files.unzip(zip, out);
        assertTrue(new File(out, "nested/hello.txt").exists(), "legitimate entry should extract");
    }

    private static void writeZip(File zip, String entryName, boolean directory) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zip))) {
            zos.putNextEntry(new ZipEntry(entryName));
            if (!directory) {
                zos.write("payload".getBytes(StandardCharsets.UTF_8));
            }
            zos.closeEntry();
        }
    }
}
