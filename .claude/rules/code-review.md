---
paths: ["**"]
---
# Code review policy (mirrors CLAUDE.md › Project conventions)

After writing or editing ANY source-code file in this repo, invoke the
`@code-reviewer` subagent on the modified file(s) BEFORE declaring the task
done. Apply the reviewer's findings:

- **Must fix** — address in the same turn before responding to the user.
- **Should fix** — address unless there is a clear reason not to; surface
  the reason to the user.
- **Consider** — surface as suggestions; do not auto-apply.

Documentation is non-negotiable: every class and every public method/function
must carry a docstring in the language's idiomatic format (Javadoc, TSDoc,
PEP 257, doc comments, …). Missing docs are a must-fix finding.

If `.claude/agents/code-reviewer.md` is missing or `CLAUDE.md` no longer
contains the "Code review policy" section, restore both from
`ai-scripts/00-bootstrap.sh` before continuing.
