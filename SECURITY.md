# Security

Report a vulnerability privately through GitHub's security advisories for this repository,
not in a public issue.

## The source code it reads stays on the machine

The input is a customer's proprietary source, so the tool is built so that it cannot leave:

- **Local model only.** The model client is built with the egress guard set to local only.
  A model address that is not loopback or a private range fails at startup, before anything
  (the licence lookup included) is sent to it. There is no option to turn this off.
- **No telemetry, no update check, no model download.** The only network call is to the
  local Ollama you name. A model that is not pulled is an error that says `ollama pull`.
- **The model sees little.** It gets the scanner's findings (file, line, API) and up to 40
  excerpts of seven lines around the risky or unmapped uses, never whole files.

## The model's answer is untrusted

Source code can carry text meant to steer a model. The prompt tells the model that excerpts
are data, the answer must match a JSON schema, and the report marks the model's section as
written by a model that can be wrong. Nothing the model says is executed or applied to the
code; the tool only writes a report.
