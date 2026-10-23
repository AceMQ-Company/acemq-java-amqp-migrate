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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.acemq.ai.ModelException;
import org.acemq.ai.test.ScriptedModel;
import org.junit.jupiter.api.Test;

class ReportTest {

    static final String ADVICE = "{\"summary\":\"Two services publish inside AMQP transactions.\","
            + "\"risks\":[\"LegacyBilling commits a transaction per publish\"],"
            + "\"order\":[\"1. Move OrderListener first\",\"Then LegacyBilling, with an outbox\"]}";

    @Test
    void theDeterministicReportHasEverySection() {
        Scanner.Result scan = ScannerTest.scanSample();
        String md = new Report(scan, Duration.ofMillis(40), null, null, Instant.parse("2026-10-23T12:00:00Z")).markdown();
        assertThat(md).contains("# Migration report: sample", "| Java files | 5 scanned, 4 use a messaging API |",
                "none (`--no-model`)", "## By source", "## What maps to what", "## No direct equivalent",
                "## Step by step", "## Risks", "Remove the old client");
        assertThat(md).contains("`org.springframework.amqp.rabbit.core.RabbitTemplate`",
                "mq.publisher(exchange, routingKey)", "`txSelect(`, `txCommit(`", "Use the outbox");
        assertThat(md).doesNotContain("## The model's reading");
        // The steps come in order and name the files they touch.
        assertThat(md.indexOf("replace the connection setup")).isLessThan(md.indexOf("Replace publishing"));
        assertThat(md).contains("`src/main/java/com/example/orders/LegacyBilling.java`");
    }

    @Test
    void theModelSeesFindingsAndExcerptsAndItsAnswerIsInTheReport() {
        Scanner.Result scan = ScannerTest.scanSample();
        ScriptedModel model = ScriptedModel.named("qwen3-coder:30b").reply(ADVICE, 900, 120);
        Advisor.Outcome advice = new Advisor(model, 2048).advise(scan);

        assertThat(model.callCount()).isEqualTo(1);
        String prompt = model.calls().get(0).messages().get(0).text();
        assertThat(prompt).contains("rabbit-tx", "LegacyBilling.java:15", "ch.txSelect();");
        assertThat(model.calls().get(0).responseSchema()).isPresent();
        assertThat(advice.usage().inputTokens()).isEqualTo(900);

        Report report = new Report(scan, Duration.ofMillis(40),
                new Report.ModelRun("qwen3-coder:30b", "Apache License 2.0", "http://localhost:11434"), advice,
                Instant.parse("2026-10-23T12:00:00Z"));
        assertThat(report.markdown()).contains("| Model | `qwen3-coder:30b` at http://localhost:11434, local only |",
                "| Model licence | Apache License 2.0 |", "900 input tokens, 120 output tokens, 1 call",
                "## The model's reading", "Two services publish inside AMQP transactions.",
                "1. Move OrderListener first").doesNotContain("1. 1.");
    }

    @Test
    void aReplyThatIsNotTheSchemaEarnsOneRepairAndNoMore() {
        Scanner.Result scan = ScannerTest.scanSample();
        ScriptedModel model = ScriptedModel.create().reply("not json", 10, 2).reply(ADVICE, 20, 30);
        Advisor.Outcome advice = new Advisor(model, 2048).advise(scan);
        assertThat(model.callCount()).isEqualTo(2);
        assertThat(advice.calls()).isEqualTo(2);
        assertThat(advice.usage().inputTokens()).isEqualTo(30);

        ScriptedModel stubborn = ScriptedModel.create().reply("not json").reply("still not json").reply(ADVICE);
        assertThatThrownBy(() -> new Advisor(stubborn, 2048).advise(scan)).isInstanceOf(ModelException.class);
        assertThat(stubborn.callCount()).isEqualTo(2);
    }

    @Test
    void theJsonCarriesEveryFindingWithItsEquivalent() throws Exception {
        Scanner.Result scan = ScannerTest.scanSample();
        JsonNode json = new ObjectMapper().readTree(
                new Report(scan, Duration.ZERO, null, null, Instant.parse("2026-10-23T12:00:00Z")).json());
        assertThat(json.path("findings").size()).isEqualTo(scan.findings().size());
        JsonNode first = json.path("findings").get(0);
        assertThat(first.has("acemqEquivalent")).isTrue();
        assertThat(first.has("confidence")).isTrue();
        assertThat(json.has("model")).isFalse();
    }
}
