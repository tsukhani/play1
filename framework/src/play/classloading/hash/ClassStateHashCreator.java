package play.classloading.hash;

import play.vfs.VirtualFile;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ClassStateHashCreator {

    private final Pattern classDefFinderPattern = Pattern.compile("\\s+class\\s([a-zA-Z0-9_]+)\\s+");

    private static class FileWithClassDefs {
        private final File file;
        private final long size;
        private final long lastModified;
        private final String classDefs;

        private FileWithClassDefs(File file, String classDefs) {
            this.file = file;
            this.classDefs = classDefs;

            // store size and time for this file..
            size = file.length();
            lastModified = file.lastModified();
        }

        /**
         * @return true if file has changed on disk
         */
        public boolean fileNotChanges( ) {
            return size == file.length() && lastModified == file.lastModified();
        }

        public String getClassDefs() {
            return classDefs;
        }
    }

    /**
     * Audit M11: cap the cache so it can't grow unbounded across many DEV
     * reloads. LinkedHashMap with access-order eviction keeps the recently-used
     * files warm — a typical app has well under 4096 .java files, so the cap is
     * effectively no-op in practice; long-running DEV sessions that scan
     * additional files (renamed/moved sources, modules adding paths) no longer
     * accumulate stale entries forever.
     */
    private static final int MAX_CACHE_SIZE = 4096;

    private final Map<File, FileWithClassDefs> classDefsInFileCache = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<File, FileWithClassDefs> eldest) {
            return size() > MAX_CACHE_SIZE;
        }
    };

    public synchronized int computePathHash(List<VirtualFile> paths) {
        StringBuilder buf = new StringBuilder();
        for (VirtualFile virtualFile : paths) {
            scan(buf, virtualFile);
        }
        // TODO: should use better hashing-algorithm.. MD5? SHA1?
        // I think hashCode() has too many collisions..
        return buf.toString().hashCode();
    }

    private void scan(StringBuilder buf, VirtualFile current) {
        if (!current.isDirectory()) {
            if (current.getName().endsWith(".java")) {
                buf.append( getClassDefsForFile(current));
            }
        } else if (!current.getName().startsWith(".")) {
            // TODO: we could later optimize it further if we check if the entire folder is unchanged
            for (VirtualFile virtualFile : current.list()) {
                scan(buf, virtualFile);
            }
        }
    }

    private String getClassDefsForFile( VirtualFile current ) {

        File realFile = current.getRealFile();
        // first we look in cache

        FileWithClassDefs fileWithClassDefs = classDefsInFileCache.get( realFile );
        if( fileWithClassDefs != null && fileWithClassDefs.fileNotChanges() ) {
            // found the file in cache and it has not changed on disk
            return fileWithClassDefs.getClassDefs();
        }

        // didn't find it or it has changed on disk
        // we must re-parse it

        StringBuilder buf = new StringBuilder();
        Matcher matcher = classDefFinderPattern.matcher(current.contentAsString());
        buf.append(current.getName());
        buf.append("(");
        while (matcher.find()) {
            buf.append(matcher.group(1));
            buf.append(",");
        }
        buf.append(")");
        String classDefs = buf.toString();

        // store it in cache
        classDefsInFileCache.put( realFile, new FileWithClassDefs(realFile, classDefs));
        return classDefs;
    }


}
