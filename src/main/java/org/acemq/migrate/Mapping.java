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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * One row of the mapping table ({@code mappings.tsv}): a source API, how to recognise it, and
 * what it becomes in AceMQ. The table is data, so a reviewer can read and correct it without
 * reading Java.
 */
public record Mapping(String id, String source, Kind kind, Pattern pattern, int step, Risk risk,
        String acemq, String note) {

    public enum Kind { IMPORT, USAGE }

    public enum Risk { LOW, MEDIUM, HIGH }

    /** The packages a file must import from for a {@code usage} row to count in it. */
    static final Map<String, Pattern> FAMILIES = Map.of(
            "spring-amqp", Pattern.compile("^(org\\.springframework\\.amqp|org\\.springframework\\.boot\\.autoconfigure\\.amqp)\\."),
            "rabbitmq-client", Pattern.compile("^com\\.rabbitmq\\.client\\."),
            "jms", Pattern.compile("^(javax\\.jms|jakarta\\.jms|org\\.springframework\\.jms)\\."));

    /** @return whether AceMQ has no direct equivalent and the row says what to do instead */
    public boolean noEquivalent() {
        return acemq.startsWith("none:");
    }

    /** The table shipped with this tool. */
    public static List<Mapping> builtIn() {
        try (InputStream in = Mapping.class.getResourceAsStream("mappings.tsv")) {
            return parse(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static List<Mapping> parse(InputStream in) throws IOException {
        List<Mapping> rows = new ArrayList<>();
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String line;
        int number = 0;
        while ((line = reader.readLine()) != null) {
            number++;
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] c = line.split("\t", -1);
            if (c.length < 7) {
                throw new IllegalArgumentException("mappings.tsv line " + number + ": expected 7 or 8 columns, got " + c.length);
            }
            if (!FAMILIES.containsKey(c[1])) {
                throw new IllegalArgumentException("mappings.tsv line " + number + ": unknown source " + c[1]);
            }
            rows.add(new Mapping(c[0], c[1], Kind.valueOf(c[2].toUpperCase()), Pattern.compile(c[3]),
                    Integer.parseInt(c[4]), Risk.valueOf(c[5].toUpperCase()), c[6], c.length > 7 ? c[7] : ""));
        }
        return rows;
    }
}
