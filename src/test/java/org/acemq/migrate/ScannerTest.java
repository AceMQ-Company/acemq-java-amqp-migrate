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

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

class ScannerTest {

    static Path sample() {
        try {
            return Path.of(ScannerTest.class.getResource("/sample").toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    static Scanner.Result scanSample() {
        return new Scanner(Mapping.builtIn()).scan(sample());
    }

    private static Set<String> ids(List<Finding> findings) {
        return findings.stream().map(f -> f.mapping().id()).collect(Collectors.toSet());
    }

    @Test
    void theShippedTableLoadsAndEveryRowHasAStepAndAnEquivalent() {
        List<Mapping> rows = Mapping.builtIn();
        assertThat(rows).hasSizeGreaterThan(40);
        assertThat(rows).allSatisfy(m -> {
            assertThat(m.step()).isBetween(1, 7);
            assertThat(m.acemq()).isNotBlank();
        });
        assertThat(rows.stream().map(Mapping::id).distinct().count()).isEqualTo(rows.size());
    }

    @Test
    void findsEachSourceFamilyInTheSample() {
        Scanner.Result r = scanSample();
        assertThat(r.filesScanned()).isEqualTo(5);
        assertThat(r.messagingFiles()).hasSize(4);
        assertThat(ids(r.findings())).contains(
                "spring-rabbit-template", "spring-convert-and-send", "spring-receive", "spring-tx",
                "spring-rabbit-listener", "spring-declarables",
                "rabbit-connection-factory", "rabbit-connection", "rabbit-recovery", "rabbit-declare",
                "rabbit-qos", "rabbit-tx", "rabbit-publish",
                "jms-connection-factory", "jms-session", "jms-selector", "jms-transacted");
    }

    @Test
    void aUsageCountsOnlyInAFileThatImportsItsFamilyAndNeverInAComment() {
        Scanner.Result r = scanSample();
        assertThat(r.findings()).noneMatch(f -> f.file().endsWith("Order.java"));
        // OrderPublisher's comment names basicPublish( and convertAndSend(; only the real call counts.
        assertThat(r.findings().stream().filter(f -> f.mapping().id().equals("spring-convert-and-send")))
                .singleElement().satisfies(f -> assertThat(f.line()).isEqualTo(18));
        assertThat(r.findings()).noneMatch(f -> f.file().endsWith("OrderPublisher.java")
                && f.mapping().id().equals("rabbit-publish"));
        // A listener method called receive(...) is not RabbitTemplate.receive.
        assertThat(r.findings()).noneMatch(f -> f.file().endsWith("OrderListener.java")
                && f.mapping().id().equals("spring-receive"));
    }

    @Test
    void importsAreHighConfidenceAndUsagesMedium() {
        Scanner.Result r = scanSample();
        assertThat(r.findings()).allSatisfy(f -> assertThat(f.confidence()).isEqualTo(
                f.mapping().kind() == Mapping.Kind.IMPORT ? Finding.Confidence.HIGH : Finding.Confidence.MEDIUM));
    }

    @Test
    void stripCommentsKeepsLinesAndStrings() {
        String src = "a // x\n/* y\n z */ b \"// not a comment\"\n";
        String out = Scanner.stripComments(src);
        assertThat(out.split("\n", -1)).hasSize(4);
        assertThat(out).doesNotContain("x").doesNotContain("y").contains("\"// not a comment\"").contains("b");
    }
}
