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

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** The migration report, as Markdown for people and as JSON for tools. */
public final class Report {

    /** The migration order. A step with no finding is left out of the report. */
    static final Map<Integer, String> STEPS = Map.of(
            1, "Add acemq-java-amqp (or the Spring Boot starter) and replace the connection setup",
            2, "Move queue, exchange and binding declarations to a Topology",
            3, "Replace publishing: publishers, converters, confirms and returns",
            4, "Replace consuming: listeners, containers, prefetch and polling",
            5, "Replace hand-written retries, acks and dead-lettering with a RetryPolicy",
            6, "Replace request-reply",
            7, "Redesign what has no equivalent: transactions, selectors, XA",
            8, "Remove the old client and its configuration");

    /** Which model ran, if one did, for the report's header. */
    public record ModelRun(String model, String licence, String endpoint) {
    }

    private final Scanner.Result scan;
    private final ModelRun model;
    private final Advisor.Outcome advice;
    private final Duration scanTook;
    private final Instant at;

    public Report(Scanner.Result scan, Duration scanTook, ModelRun model, Advisor.Outcome advice, Instant at) {
        this.scan = scan;
        this.scanTook = scanTook;
        this.model = model;
        this.advice = advice;
        this.at = at;
    }

    private Map<String, List<Finding>> byRow() {
        return scan.findings().stream()
                .collect(Collectors.groupingBy(f -> f.mapping().id(), LinkedHashMap::new, Collectors.toList()));
    }

    public String markdown() {
        StringBuilder md = new StringBuilder();
        md.append("# Migration report: ").append(scan.root().getFileName()).append("\n\n");
        md.append("| | |\n|---|---|\n");
        md.append("| Generated | ").append(at).append(" |\n");
        md.append("| Source | `").append(scan.root()).append("` |\n");
        md.append("| Java files | ").append(scan.filesScanned()).append(" scanned, ")
                .append(scan.messagingFiles().size()).append(" use a messaging API |\n");
        md.append("| Findings | ").append(scan.findings().size()).append(" (")
                .append(count(Mapping.Risk.HIGH)).append(" high risk, ")
                .append(scan.findings().stream().filter(f -> f.mapping().noEquivalent()).count())
                .append(" with no direct equivalent) |\n");
        if (model == null) {
            md.append("| Model | none (`--no-model`): the deterministic mapping only |\n");
        } else {
            md.append("| Model | `").append(model.model()).append("` at ").append(model.endpoint())
                    .append(", local only |\n");
            md.append("| Model licence | ").append(model.licence()).append(" |\n");
            if (advice != null) {
                md.append("| Model usage | ").append(advice.usage().inputTokens()).append(" input tokens, ")
                        .append(advice.usage().outputTokens()).append(" output tokens, ").append(advice.calls())
                        .append(advice.calls() == 1 ? " call, " : " calls, ")
                        .append(seconds(advice.took())).append(" on this machine |\n");
            }
        }
        md.append("| Scan time | ").append(seconds(scanTook)).append(" |\n\n");

        if (scan.findings().isEmpty()) {
            md.append("No use of Spring AMQP, the RabbitMQ Java client or JMS was found.\n");
            return md.toString();
        }

        md.append("## By source\n\n| Source | Findings | High risk | Files |\n|---|---|---|---|\n");
        new TreeMap<>(scan.findings().stream().collect(Collectors.groupingBy(f -> f.mapping().source())))
                .forEach((source, list) -> md.append("| ").append(source).append(" | ").append(list.size())
                        .append(" | ").append(list.stream().filter(f -> f.mapping().risk() == Mapping.Risk.HIGH).count())
                        .append(" | ").append(list.stream().map(Finding::file).distinct().count()).append(" |\n"));

        md.append("\n## What maps to what\n\n| Source API | AceMQ | Risk | Uses | Where |\n|---|---|---|---|---|\n");
        byRow().forEach((id, list) -> {
            Mapping m = list.get(0).mapping();
            if (!m.noEquivalent()) {
                md.append("| ").append(escape(apis(list))).append(" | ").append(escape(m.acemq()))
                        .append(" | ").append(m.risk().name().toLowerCase()).append(" | ").append(list.size())
                        .append(" | ").append(where(list)).append(" |\n");
            }
        });

        List<Map.Entry<String, List<Finding>>> none = byRow().entrySet().stream()
                .filter(e -> e.getValue().get(0).mapping().noEquivalent()).toList();
        if (!none.isEmpty()) {
            md.append("\n## No direct equivalent\n\n");
            for (Map.Entry<String, List<Finding>> e : none) {
                Mapping m = e.getValue().get(0).mapping();
                md.append("- **").append(apis(e.getValue())).append("** (").append(e.getValue().size())
                        .append(e.getValue().size() == 1 ? " use" : " uses").append(": ").append(where(e.getValue()))
                        .append("). ").append(sentence(instead(m))).append('.');
                if (!m.note().isEmpty()) {
                    md.append(' ').append(m.note());
                }
                md.append('\n');
            }
        }

        md.append("\n## Step by step\n\n");
        Map<Integer, List<Finding>> bySteps = new TreeMap<>(scan.findings().stream()
                .collect(Collectors.groupingBy(f -> f.mapping().step())));
        int n = 1;
        for (Map.Entry<Integer, List<Finding>> e : bySteps.entrySet()) {
            List<String> files = e.getValue().stream().map(f -> f.file().toString().replace('\\', '/'))
                    .distinct().sorted().toList();
            md.append(n++).append(". ").append(STEPS.get(e.getKey())).append(" — ")
                    .append(files.size()).append(files.size() == 1 ? " file: " : " files: ")
                    .append(files.stream().limit(8).map(f -> "`" + f + "`").collect(Collectors.joining(", ")))
                    .append(files.size() > 8 ? ", and " + (files.size() - 8) + " more" : "").append('\n');
        }
        md.append(n).append(". ").append(STEPS.get(8)).append('\n');

        md.append("\n## Risks\n\n");
        List<String> risks = new ArrayList<>();
        byRow().forEach((id, list) -> {
            Mapping m = list.get(0).mapping();
            if (m.risk() == Mapping.Risk.HIGH) {
                risks.add("**" + m.id() + "** (" + where(list) + "): " + sentence(m.note().isEmpty() ? instead(m) : m.note()));
            }
        });
        if (risks.isEmpty()) {
            md.append("None of the uses found is rated high risk by the mapping table.\n");
        } else {
            risks.forEach(r -> md.append("- ").append(r).append('\n'));
        }

        if (advice != null) {
            Advisor.Advice a = advice.advice();
            md.append("\n## The model's reading\n\n");
            md.append("> Written by `").append(model.model())
                    .append("` from the findings and short excerpts. A model can be wrong; check it against the code.\n\n");
            md.append(a.summary()).append("\n\n");
            if (a.risks() != null && !a.risks().isEmpty()) {
                md.append("Risks it sees:\n\n");
                a.risks().forEach(r -> md.append("- ").append(r).append('\n'));
                md.append('\n');
            }
            if (a.order() != null && !a.order().isEmpty()) {
                md.append("Order it suggests:\n\n");
                int i = 1;
                for (String s : a.order()) {
                    // Models number their own steps; the list does that already.
                    md.append(i++).append(". ").append(s.replaceFirst("^\\s*\\d+[.)]\\s*", "")).append('\n');
                }
            }
        }
        return md.toString();
    }

    public String json() {
        ObjectMapper json = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        ObjectNode root = json.createObjectNode();
        root.put("generated", at.toString());
        root.put("source", scan.root().toString());
        root.put("filesScanned", scan.filesScanned());
        root.put("messagingFiles", scan.messagingFiles().size());
        if (model != null) {
            root.putObject("model").put("name", model.model()).put("licence", model.licence())
                    .put("endpoint", model.endpoint());
        }
        ArrayNode findings = root.putArray("findings");
        for (Finding f : scan.findings()) {
            findings.addObject().put("file", f.file().toString().replace('\\', '/')).put("line", f.line())
                    .put("mapping", f.mapping().id()).put("source", f.mapping().source())
                    .put("sourceApi", f.sourceApi()).put("acemqEquivalent", f.mapping().acemq())
                    .put("confidence", f.confidence().name().toLowerCase())
                    .put("risk", f.mapping().risk().name().toLowerCase()).put("step", f.mapping().step());
        }
        if (advice != null) {
            ObjectNode a = root.putObject("advice");
            a.put("summary", advice.advice().summary());
            a.set("risks", json.valueToTree(advice.advice().risks()));
            a.set("order", json.valueToTree(advice.advice().order()));
            a.putObject("usage").put("inputTokens", advice.usage().inputTokens())
                    .put("outputTokens", advice.usage().outputTokens()).put("calls", advice.calls())
                    .put("seconds", advice.took().toMillis() / 1000.0);
        }
        try {
            return json.writeValueAsString(root) + "\n";
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private long count(Mapping.Risk risk) {
        return scan.findings().stream().filter(f -> f.mapping().risk() == risk).count();
    }

    private static String where(List<Finding> list) {
        String shown = list.stream().limit(3).map(f -> "`" + f.where() + "`").collect(Collectors.joining(", "));
        return list.size() > 3 ? shown + " and " + (list.size() - 3) + " more" : shown;
    }

    /** The distinct source APIs a row matched, as code. */
    private static String apis(List<Finding> list) {
        List<String> distinct = list.stream().map(Finding::sourceApi).distinct().toList();
        String shown = distinct.stream().limit(3).map(a -> "`" + a + "`").collect(Collectors.joining(", "));
        return distinct.size() > 3 ? shown + " and " + (distinct.size() - 3) + " more" : shown;
    }

    private static String instead(Mapping m) {
        return m.noEquivalent() ? m.acemq().substring("none:".length()).trim() : m.acemq();
    }

    private static String sentence(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String escape(String s) {
        return s.replace("|", "\\|");
    }

    private static String seconds(Duration d) {
        return String.format(java.util.Locale.ROOT, "%.1f s", d.toMillis() / 1000.0);
    }
}
