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
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.function.Function;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.acemq.ai.Egress;
import org.acemq.ai.ModelClient;
import org.acemq.ai.ModelClients;

/**
 * The command line.
 *
 * <pre>
 * java -jar acemq-java-amqp-migrate-all.jar &lt;source-dir&gt; [--out report.md] [--json report.json]
 *     [--model qwen3-coder:30b] [--ollama http://localhost:11434] [--no-model] [--max-output-tokens 4096]
 * </pre>
 *
 * <p>The model is a local Ollama and nothing else: the egress guard is {@link Egress#LOCAL_ONLY},
 * so a non-local address fails at startup. The input is a customer's source, and it does not
 * leave the machine.
 */
public final class Migrate {

    static final String DEFAULT_MODEL = "qwen3-coder:30b";

    private Migrate() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err, Migrate::ollama));
    }

    /** What the command was asked. */
    record Options(Path source, Path out, Path json, String model, String ollama, boolean useModel, int maxOutputTokens) {
    }

    static int run(String[] args, PrintStream out, PrintStream err, Function<Options, ModelClient> models) {
        Options o;
        try {
            o = parse(args);
        } catch (IllegalArgumentException e) {
            err.println("acemq-migrate: " + e.getMessage());
            err.println(USAGE);
            return 2;
        }
        if (o == null) {
            out.println(USAGE);
            return 0;
        }
        if (!Files.isDirectory(o.source())) {
            err.println("acemq-migrate: " + o.source() + " is not a directory");
            return 2;
        }

        long start = System.nanoTime();
        Scanner.Result scan = new Scanner(Mapping.builtIn()).scan(o.source().toAbsolutePath().normalize());
        Duration scanTook = Duration.ofNanos(System.nanoTime() - start);
        err.println("acemq-migrate: " + scan.filesScanned() + " Java files, " + scan.messagingFiles().size()
                + " use a messaging API, " + scan.findings().size() + " findings");

        Report.ModelRun run = null;
        Advisor.Outcome advice = null;
        if (o.useModel() && !scan.findings().isEmpty()) {
            ModelClient model;
            String licence;
            try {
                // The client first: its egress guard refuses a non-local address before
                // anything, the licence lookup included, is sent there.
                model = models.apply(o);
                licence = licence(o.ollama(), o.model());
            } catch (RuntimeException e) {
                err.println("acemq-migrate: " + e.getMessage());
                return 2;
            }
            run = new Report.ModelRun(o.model(), licence, o.ollama());
            err.println("acemq-migrate: asking " + o.model() + " (" + licence + ") at " + o.ollama());
            try (model) {
                advice = new Advisor(model, o.maxOutputTokens()).advise(scan);
            } catch (Exception e) {
                err.println("acemq-migrate: the model step failed: " + e.getMessage());
                err.println("acemq-migrate: run again with --no-model for the deterministic report");
                return 2;
            }
        }

        Report report = new Report(scan, scanTook, run, advice, Instant.now().truncatedTo(ChronoUnit.SECONDS));
        try {
            if (o.out() == null) {
                out.print(report.markdown());
            } else {
                Files.writeString(o.out(), report.markdown(), StandardCharsets.UTF_8);
                err.println("acemq-migrate: wrote " + o.out());
            }
            if (o.json() != null) {
                Files.writeString(o.json(), report.json(), StandardCharsets.UTF_8);
                err.println("acemq-migrate: wrote " + o.json());
            }
        } catch (IOException e) {
            err.println("acemq-migrate: cannot write the report: " + e.getMessage());
            return 2;
        }
        return 0;
    }

    static final String USAGE = """
            usage: acemq-migrate <source-dir> [options]

              --out <file>              write the Markdown report there (default: stdout)
              --json <file>             also write the findings as JSON
              --model <name>            the local Ollama model (default qwen3-coder:30b)
              --ollama <url>            where Ollama listens (default $OLLAMA_HOST or http://localhost:11434)
              --no-model                the deterministic report only; nothing is asked of a model
              --max-output-tokens <n>   the model's answer limit (default 4096)

            Exit status: 0 the report was written, 2 it could not be (the reason is on stderr).""";

    static Options parse(String[] args) {
        Path source = null;
        Path out = null;
        Path json = null;
        String model = DEFAULT_MODEL;
        String host = System.getenv("OLLAMA_HOST");
        String ollama = host == null || host.isBlank() ? "http://localhost:11434"
                : host.startsWith("http") ? host : "http://" + host;
        boolean useModel = true;
        int max = 4096;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "-h", "--help" -> {
                    return null;
                }
                case "--out" -> out = Path.of(value(args, ++i, a));
                case "--json" -> json = Path.of(value(args, ++i, a));
                case "--model" -> model = value(args, ++i, a);
                case "--ollama" -> ollama = value(args, ++i, a);
                case "--no-model" -> useModel = false;
                case "--max-output-tokens" -> {
                    try {
                        max = Integer.parseInt(value(args, ++i, a));
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException("--max-output-tokens takes a number");
                    }
                }
                default -> {
                    if (a.startsWith("-")) {
                        throw new IllegalArgumentException("unknown option " + a);
                    }
                    if (source != null) {
                        throw new IllegalArgumentException("one source directory, not two");
                    }
                    source = Path.of(a);
                }
            }
        }
        if (source == null) {
            throw new IllegalArgumentException("name the source directory to analyse");
        }
        return new Options(source, out, json, model, ollama.replaceAll("/+$", ""), useModel, max);
    }

    private static String value(String[] args, int i, String option) {
        if (i >= args.length) {
            throw new IllegalArgumentException(option + " needs a value");
        }
        return args[i];
    }

    /** The real model: a local Ollama, local only. */
    static ModelClient ollama(Options o) {
        return ModelClients.ollama(o.model()).baseUrl(o.ollama()).egress(Egress.LOCAL_ONLY)
                .timeout(Duration.ofMinutes(30)).build();
    }

    /**
     * The model's licence as Ollama reports it ({@code POST /api/show}), for the report header:
     * a customer will ask what ran on their code, under what terms. Also the startup check that
     * Ollama is there and has the model.
     */
    static String licence(String ollama, String model) {
        HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5)).build();
        String body = "{\"model\":" + new ObjectMapper().valueToTree(model) + "}";
        HttpResponse<String> response;
        try {
            response = http.send(HttpRequest.newBuilder(URI.create(ollama + "/api/show"))
                    .timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IllegalStateException("Ollama is not answering at " + ollama
                    + ". Start it (ollama serve), or run with --no-model for the deterministic report.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted");
        }
        if (response.statusCode() == 404) {
            throw new IllegalStateException("Ollama at " + ollama + " has no model '" + model
                    + "'. Pull it first: ollama pull " + model);
        }
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("Ollama at " + ollama + " answered HTTP " + response.statusCode());
        }
        try {
            JsonNode show = new ObjectMapper().readTree(response.body());
            String licence = show.path("license").asText("").strip();
            if (licence.isEmpty()) {
                return "not stated by the model (check it before sharing this report)";
            }
            String first = licence.lines().filter(l -> !l.isBlank()).findFirst().orElse("").strip();
            return first.length() > 120 ? first.substring(0, 120) + "…" : first;
        } catch (IOException e) {
            return "not stated by the model (check it before sharing this report)";
        }
    }
}
