/*
 * Copyright 2026 AceMQ.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.acemq.migrate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.MalformedInputException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Finds every use of a messaging API the mapping table knows, file by file, without a model.
 *
 * <p>An import row matches an import line; a usage row matches code, and only in a file that
 * imports from that row's packages, so a method that happens to be called {@code basicPublish}
 * in unrelated code is not reported. Comments are blanked first, keeping line numbers.
 */
public final class Scanner {

    private static final Pattern IMPORT = Pattern.compile("^\\s*import\\s+(?:static\\s+)?([\\w.]+)(?:\\.\\*)?\\s*;");
    private static final Set<String> SKIPPED = Set.of("target", "build", ".git", "node_modules", ".gradle", ".idea", "out");

    // ponytail: line-based regex over comment-stripped source, not a Java parser. It
    // misses an API reached only through a fully qualified name with no import, and a call
    // split across lines. Upgrade to a parser when a customer codebase shows the gap.

    private final List<Mapping> mappings;

    public Scanner(List<Mapping> mappings) {
        this.mappings = mappings;
    }

    /** What a scan found. */
    public record Result(Path root, int filesScanned, List<Path> messagingFiles, List<Finding> findings) {
    }

    public Result scan(Path root) {
        List<Path> files;
        try (Stream<Path> walk = Files.walk(root)) {
            files = walk.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> root.relativize(p).getNameCount() == 1 || !skipped(root.relativize(p)))
                    .sorted(Comparator.naturalOrder())
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + root, e);
        }
        List<Finding> findings = new ArrayList<>();
        List<Path> messaging = new ArrayList<>();
        for (Path file : files) {
            List<Finding> here = scanFile(root, file);
            if (!here.isEmpty()) {
                messaging.add(root.relativize(file));
                findings.addAll(here);
            }
        }
        return new Result(root, files.size(), messaging, findings);
    }

    private static boolean skipped(Path relative) {
        for (Path part : relative) {
            if (SKIPPED.contains(part.toString())) {
                return true;
            }
        }
        return false;
    }

    List<Finding> scanFile(Path root, Path file) {
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (MalformedInputException e) {
            try {
                text = Files.readString(file, StandardCharsets.ISO_8859_1);
            } catch (IOException again) {
                throw new UncheckedIOException(again);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        String[] lines = stripComments(text).split("\n", -1);
        Path relative = root.relativize(file);

        Set<String> families = new HashSet<>();
        List<Finding> found = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            Matcher m = IMPORT.matcher(lines[i]);
            if (!m.find()) {
                continue;
            }
            String imported = m.group(1);
            Mapping.FAMILIES.forEach((family, prefix) -> {
                if (prefix.matcher(imported).find()) {
                    families.add(family);
                }
            });
            for (Mapping row : mappings) {
                if (row.kind() == Mapping.Kind.IMPORT && row.pattern().matcher(imported).find()) {
                    found.add(new Finding(relative, i + 1, row, imported, Finding.Confidence.HIGH));
                }
            }
        }
        if (families.isEmpty()) {
            return found;
        }
        for (int i = 0; i < lines.length; i++) {
            if (IMPORT.matcher(lines[i]).find()) {
                continue;
            }
            for (Mapping row : mappings) {
                if (row.kind() != Mapping.Kind.USAGE || !families.contains(row.source())) {
                    continue;
                }
                Matcher m = row.pattern().matcher(lines[i]);
                if (m.find()) {
                    found.add(new Finding(relative, i + 1, row, m.group().trim(), Finding.Confidence.MEDIUM));
                }
            }
        }
        return found;
    }

    /** Comments become spaces, newlines kept, so line numbers still point at the source. */
    static String stripComments(String text) {
        StringBuilder out = new StringBuilder(text.length());
        int i = 0;
        boolean inString = false;
        char quote = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            char next = i + 1 < text.length() ? text.charAt(i + 1) : 0;
            if (inString) {
                out.append(c);
                if (c == '\\' && next != 0) {
                    out.append(next);
                    i += 2;
                    continue;
                }
                if (c == quote || c == '\n') {
                    inString = false;
                }
                i++;
            } else if (c == '"' || c == '\'') {
                inString = true;
                quote = c;
                out.append(c);
                i++;
            } else if (c == '/' && next == '/') {
                while (i < text.length() && text.charAt(i) != '\n') {
                    out.append(' ');
                    i++;
                }
            } else if (c == '/' && next == '*') {
                out.append("  ");
                i += 2;
                while (i < text.length() && !(text.charAt(i) == '*' && i + 1 < text.length() && text.charAt(i + 1) == '/')) {
                    out.append(text.charAt(i) == '\n' ? '\n' : ' ');
                    i++;
                }
                out.append("  ");
                i += 2;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }
}
