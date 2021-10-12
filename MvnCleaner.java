///usr/bin/env jbang "$0" "$@" ; exit $?

import java.io.IOException;
import java.lang.module.ModuleDescriptor;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

public class MvnCleaner {
    public static final Map<String, ModuleDescriptor.Version> latestDeps = new HashMap<>();
    public static final Map<String, List<ModuleDescriptor.Version>> deleteDeps = new HashMap<>();

    public static void main(String[] args) throws IOException {
        final var dry = args != null && args.length > 0 && Arrays.stream(args).anyMatch(p -> "--dry".equalsIgnoreCase(p) || "-d".equalsIgnoreCase(p));
        final var print = args != null && args.length > 0 && Arrays.stream(args).anyMatch(p -> "--print".equalsIgnoreCase(p) || "-p".equalsIgnoreCase(p));
        Files.walk(Paths.get(System.getenv("HOME"), ".m2", "repository"), FileVisitOption.FOLLOW_LINKS)
                .forEach(MvnCleaner::process);

        if (dry) {
            System.out.println("Running in DRY RUN mode");
        }
        
        if (deleteDeps.isEmpty()) {
            System.out.println("Nothing found to delete");
        }

        var totalRemoved = new AtomicLong(0);
        for (var e : deleteDeps.entrySet()) {
            if (dry || print) {
                System.out.println("for lib: " + e.getKey() + " keeping version: " + latestDeps.get(e.getKey()) + " and deleting: " + e.getValue());
            }

            for (final var v : e.getValue()) {
                try (var walk = Files.walk(Paths.get(e.getKey(), v.toString()))) {
                    walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                        try {
                            totalRemoved.addAndGet(Files.size(p));
                            if (!dry) {
                                Files.deleteIfExists(p);
                            }
                        } catch (IOException ex) {
                            System.err.println("error deleting: " + p);
                            ex.printStackTrace();
                        }
                    });
                }
            }
        }
        System.out.println((dry ? "Would remove " : "Removed ") + totalRemoved.get() + "B / " + (totalRemoved.get() / 1024) + "KB / " + (totalRemoved.get()/ (1024*1024)) + "MB");
    }

    public static void process(Path path) {
        final var fileName = path.getFileName().toString();
        ModuleDescriptor.Version v;
        try {
            v = ModuleDescriptor.Version.parse(fileName);
        } catch (Exception e) {
            return;
        }

        //parent path keeps the library name without version (e.g. /home/jchrist/.m2/repository/commons-io/commons-io)
        final var lib = path.getParent().toString();
        if (!latestDeps.containsKey(lib)) {
            latestDeps.put(lib, v);
            return;
        }
        // latest deps contains already a version, so let's see which one should be kept
        final var exv = latestDeps.get(lib);
        if (v.compareTo(exv) > 0) {
            //this version is greater, so replace it
            latestDeps.put(lib, v);
            deleteDeps.computeIfAbsent(lib, l -> new ArrayList<>()).add(exv);
        } else {
            deleteDeps.computeIfAbsent(lib, l -> new ArrayList<>()).add(v);
        }
    }
}
