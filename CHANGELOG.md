# Changelog

All notable changes to this project are documented in this file. The format
follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and this
project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

While the version is `0.x` the report's shape and the command's options may
change in any release.

This tool has its own version line, starting at `0.1.0`.

## [Unreleased]

### Added

- The first version of the migration agent: `acemq-migrate <source-dir>` reads
  a Java codebase that uses Spring AMQP, the RabbitMQ Java client or JMS and
  writes a Markdown report (and, with `--json`, the findings as JSON): what maps
  to what, what has no direct equivalent, a step-by-step order, and the risks.
- A mapping table (`mappings.tsv`, 45 rows) that finds every known API
  deterministically, with a file and line for each use.
- A local model (Ollama, default `qwen3-coder:30b`) asked once, with one repair
  if its answer does not match the schema, about the risky and unmapped uses
  only. The egress guard is local-only: a non-local model address fails before
  anything is sent. The report header names the model, its licence as Ollama
  reports it, and the tokens and time it took. `--no-model` gives the
  deterministic report alone.
