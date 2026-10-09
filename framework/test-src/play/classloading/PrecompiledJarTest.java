package play.classloading;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The jar a bundle or dist carries its precompiled classes in. An application started from its
 * working copy has no such jar and runs off the tree under {@code precompiled/java}, so "no
 * jar" has to be an answer too.
 */
public class PrecompiledJarTest {

    @Test
    public void applicationWithoutTheJarHasNone(@TempDir File app) throws IOException {
        // what play precompile leaves behind: a tree of class files and nothing else
        File inTree = new File(app, "precompiled/java/models/Note.class");
        inTree.getParentFile().mkdirs();
        Files.write(inTree.toPath(), bytes("tree"));

        assertThat(PrecompiledJar.of(app)).isNull();
    }

    @Test
    public void listsItsClassesByNameAndNothingElse(@TempDir File app) throws IOException {
        jar(app, false, "models/Note.class", "controllers/Application.class", "models/Note$Draft.class",
                "META-INF/MANIFEST.MF", "models/", "models/notes.txt", "package-info.class");

        PrecompiledJar jar = PrecompiledJar.of(app);
        try {
            // sorted, so that the order classes are defined in does not depend on who wrote the jar
            assertThat(jar.names()).containsExactly("controllers.Application", "models.Note", "models.Note$Draft", "package-info");
        } finally {
            jar.close();
        }
    }

    @Test
    public void readsAClassAndAnswersNullForOneItDoesNotHold(@TempDir File app) throws IOException {
        jar(app, false, "models/Note.class", "controllers/Application.class");

        PrecompiledJar jar = PrecompiledJar.of(app);
        try {
            assertThat(jar.read("models.Note")).isEqualTo(bytes("models/Note.class"));
            assertThat(jar.read("controllers.Application")).isEqualTo(bytes("controllers/Application.class"));
            // every class Play's loader is asked for comes by here first, the JDK's and the libraries' too
            assertThat(jar.read("java.lang.String")).isNull();
            assertThat(jar.read("models.Other")).isNull();
        } finally {
            jar.close();
        }
    }

    @Test
    public void readsCompressedEntriesToo(@TempDir File app) throws IOException {
        // this framework writes the entries stored; a tool that rewrites the jar, ProGuard for one, deflates them
        jar(app, true, "models/Note.class");

        PrecompiledJar jar = PrecompiledJar.of(app);
        try {
            assertThat(jar.read("models.Note")).isEqualTo(bytes("models/Note.class"));
        } finally {
            jar.close();
        }
    }

    @Test
    public void closingLetsGoOfTheFileAndALaterReadOpensItAgain(@TempDir File app) throws IOException {
        File file = jar(app, false, "models/Note.class");
        PrecompiledJar jar = PrecompiledJar.of(app);
        assertThat(jar.names()).containsExactly("models.Note");
        jar.close();

        // closed after the start-up scan, so the file is not held open for the life of the JVM;
        // on Windows an open jar could not be replaced by a redeploy
        File moved = new File(app, "moved.jar");
        assertThat(file.renameTo(moved)).as("the closed jar can be moved away").isTrue();
        assertThat(moved.renameTo(file)).isTrue();

        try {
            assertThat(jar.read("models.Note")).isEqualTo(bytes("models/Note.class"));
        } finally {
            jar.close();
        }
    }

    private static byte[] bytes(String entry) {
        return ("bytecode of " + entry).getBytes(StandardCharsets.UTF_8);
    }

    private static File jar(File app, boolean compressed, String... entries) throws IOException {
        File file = new File(app, PrecompiledJar.PATH);
        file.getParentFile().mkdirs();
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(file))) {
            for (String name : entries) {
                ZipEntry entry = new ZipEntry(name);
                byte[] content = name.endsWith("/") ? new byte[0] : bytes(name);
                if (!compressed) {
                    CRC32 crc = new CRC32();
                    crc.update(content);
                    entry.setMethod(ZipEntry.STORED);
                    entry.setSize(content.length);
                    entry.setCompressedSize(content.length);
                    entry.setCrc(crc.getValue());
                }
                out.putNextEntry(entry);
                out.write(content);
                out.closeEntry();
            }
        }
        return file;
    }
}
