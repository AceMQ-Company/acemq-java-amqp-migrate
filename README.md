# acemq-java-amqp-migrate

[![ci](https://github.com/AceMQ-Company/acemq-java-amqp-migrate/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/AceMQ-Company/acemq-java-amqp-migrate/actions/workflows/ci.yml)
[![authorship guard](https://github.com/AceMQ-Company/acemq-java-amqp-migrate/actions/workflows/attribution-guard.yml/badge.svg?branch=main)](https://github.com/AceMQ-Company/acemq-java-amqp-migrate/actions/workflows/attribution-guard.yml)
[![license](https://img.shields.io/badge/license-Apache--2.0-green)](LICENSE)
[![Java](https://img.shields.io/badge/Java-17%2B-orange)](#requirements)

The AceMQ migration agent. It reads a Java codebase that uses **Spring AMQP**, the **RabbitMQ
Java client** or **JMS**, and writes a migration report to
[AceMQ](https://github.com/AceMQ-Company/acemq-java-amqp): what maps to what, what has no
direct equivalent, the order to take the steps in, and the risks.

> **Status: unreleased, the first usable version.** Build it from source. The report's shape
> and the options may change in any `0.x` release.

**Nothing leaves the machine.** The input is your proprietary source. Most of the report comes
from a mapping table, with no model at all. The parts that need judgment go to a **local**
model through [Ollama](https://ollama.com), and the model client refuses any address that is
not local. There is no switch to send it elsewhere.

## Run it

```sh
mvn -q package
ollama pull qwen3-coder:30b          # about 19 GB; any local model works, see --model
java -jar target/acemq-java-amqp-migrate-*-all.jar ~/src/billing-service --out migration.md --json migration.json
```

Without a model, the deterministic report alone:

```sh
java -jar target/acemq-java-amqp-migrate-*-all.jar ~/src/billing-service --no-model
```

| Option | Default | |
|---|---|---|
| `--out <file>` | stdout | the Markdown report |
| `--json <file>` | none | every finding as JSON: file, line, source API, AceMQ equivalent, confidence, risk, step |
| `--model <name>` | `qwen3-coder:30b` | the local Ollama model |
| `--ollama <url>` | `$OLLAMA_HOST`, else `http://localhost:11434` | must be loopback or a private address |
| `--no-model` | | ask no model |
| `--max-output-tokens <n>` | 4096 | the model's answer limit |

Exit status: `0` the report was written; `2` it could not be, with the reason on stderr (no
such directory, Ollama not answering, the model not pulled, a non-local address).

## What the report says

| Section | From |
|---|---|
| Header: files, findings, the model, **its licence as Ollama reports it**, tokens and time | the run |
| By source | the scanner |
| What maps to what: each source API, its AceMQ equivalent, risk, uses, `file:line` | the mapping table |
| No direct equivalent: transactions, JMS selectors, polling gets, multi-method listeners | the mapping table |
| Step by step: connection, topology, publishing, consuming, retries, request-reply, what to redesign, removing the old client, each with its files | the mapping table |
| Risks | the mapping table |
| The model's reading: what the risky uses mean in this codebase, and an order | the model, marked as such |

## How it works

1. **Scan, deterministically.** Every `.java` file under the directory (`target`, `build`,
   `.git` and the like skipped), comments blanked. An *import* row of
   [`mappings.tsv`](src/main/resources/org/acemq/migrate/mappings.tsv) matches an import line
   exactly; a *usage* row (`basicPublish(`, `@RabbitListener`, `txSelect(`, ...) matches code,
   but only in a file that imports from that API's packages, so an unrelated method of the same
   name does not count. The table is data: 45 rows, each with its AceMQ equivalent, a risk, a
   step and a note, readable without Java.
2. **Ask the model once, about what the table cannot decide.** The prompt holds the findings
   and up to 40 seven-line excerpts around the high-risk and unmapped uses, never whole files.
   The answer must match a JSON schema (summary, risks, order). An answer that does not gets
   **one** repair request, never more. `temperature 0` and a fixed `seed`, so a re-run is as
   repeatable as the model allows.
3. **Write the report.** The model's section is labelled as the model's.

It is built on [acemq-java-amqp-ai](https://github.com/AceMQ-Company/acemq-java-amqp-ai)
(`ModelClients.ollama(...).egress(Egress.LOCAL_ONLY)`, structured output, token usage).

## Limits

- A use reached only through a fully qualified name with no import, or a call split across
  lines, is not found: the scanner is line-based, not a Java parser.
- It reads Java. Kotlin, Groovy and Scala sources, and `application.properties`, are not read.
- It writes a report. It changes no code.

## Requirements

Java 17 or newer. For the model step, a local Ollama with a model pulled.

## Licence

Apache 2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).
