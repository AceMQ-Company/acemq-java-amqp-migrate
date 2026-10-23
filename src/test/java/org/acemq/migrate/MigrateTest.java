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

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MigrateTest {

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    private int run(String... args) {
        return Migrate.run(args, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8), Migrate::ollama);
    }

    private String stdout() {
        return out.toString(StandardCharsets.UTF_8);
    }

    private String stderr() {
        return err.toString(StandardCharsets.UTF_8);
    }

    @Test
    void noModelWritesTheDeterministicReportToStdout() {
        assertThat(run(ScannerTest.sample().toString(), "--no-model")).isZero();
        assertThat(stdout()).startsWith("# Migration report: sample").contains("## Step by step");
        assertThat(stderr()).contains("5 Java files, 4 use a messaging API");
    }

    @Test
    void writesMarkdownAndJsonFiles(@TempDir Path tmp) throws IOException {
        Path md = tmp.resolve("r.md");
        Path json = tmp.resolve("r.json");
        assertThat(run(ScannerTest.sample().toString(), "--no-model", "--out", md.toString(), "--json", json.toString()))
                .isZero();
        assertThat(Files.readString(md)).contains("## What maps to what");
        assertThat(Files.readString(json)).contains("\"findings\"");
        assertThat(stdout()).isEmpty();
    }

    @Test
    void aNonLocalModelAddressIsRefusedBeforeAnythingIsSent() {
        assertThat(run(ScannerTest.sample().toString(), "--ollama", "http://example.com:11434")).isEqualTo(2);
        assertThat(stderr()).contains("example.com");
    }

    @Test
    void anOllamaThatIsNotThereSaysHowToGoOn() {
        assertThat(run(ScannerTest.sample().toString(), "--ollama", "http://127.0.0.1:9")).isEqualTo(2);
        assertThat(stderr()).contains("Ollama is not answering", "--no-model");
    }

    @Test
    void aModelThatIsNotPulledSaysHowToPullIt() throws IOException {
        HttpServer ollama = fakeOllama(404, "{\"error\":\"model 'x' not found\"}", new AtomicInteger());
        try {
            assertThat(run(ScannerTest.sample().toString(), "--model", "x", "--ollama", url(ollama))).isEqualTo(2);
            assertThat(stderr()).contains("ollama pull x");
        } finally {
            ollama.stop(0);
        }
    }

    @Test
    void withALocalModelTheReportNamesItsLicenceAndUsage() throws IOException {
        AtomicInteger chats = new AtomicInteger();
        HttpServer ollama = fakeOllama(200, "{\"license\":\"Apache License\\nVersion 2.0, January 2004\"}", chats);
        try {
            assertThat(run(ScannerTest.sample().toString(), "--ollama", url(ollama))).isZero();
            assertThat(chats).hasValue(1);
            assertThat(stdout()).contains("| Model | `qwen3-coder:30b`", "| Model licence | Apache License |",
                    "321 input tokens, 45 output tokens, 1 call", "Two services publish inside AMQP transactions.");
        } finally {
            ollama.stop(0);
        }
    }

    @Test
    void usageErrorsExitTwo() {
        assertThat(run()).isEqualTo(2);
        assertThat(run("a", "b")).isEqualTo(2);
        assertThat(run("--bogus")).isEqualTo(2);
        assertThat(run("/no/such/dir", "--no-model")).isEqualTo(2);
        assertThat(run("--help")).isZero();
    }

    /** /api/show answers with {@code status} and {@code show}; /api/chat with the advice. */
    private static HttpServer fakeOllama(int status, String show, AtomicInteger chats) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/api/show", ex -> {
            ex.getRequestBody().readAllBytes();
            send(ex.getResponseBody(), ex, status, show);
        });
        server.createContext("/api/chat", ex -> {
            ex.getRequestBody().readAllBytes();
            chats.incrementAndGet();
            String reply = "{\"model\":\"qwen3-coder:30b\",\"done\":true,\"done_reason\":\"stop\","
                    + "\"message\":{\"role\":\"assistant\",\"content\":"
                    + new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(ReportTest.ADVICE) + "},"
                    + "\"prompt_eval_count\":321,\"eval_count\":45}";
            send(ex.getResponseBody(), ex, 200, reply);
        });
        server.start();
        return server;
    }

    private static void send(OutputStream body, com.sun.net.httpserver.HttpExchange ex, int status, String text)
            throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (body) {
            body.write(bytes);
        }
    }

    private static String url(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }
}
