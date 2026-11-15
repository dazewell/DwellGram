---
applyTo: "TMessagesProj/src/main/res/**"
---

# You are editing resources

Most of this tree is upstream. Adding to the fork's own files is free; editing
an upstream one costs a merge conflict.

- **New strings go in `values/strings_nax.xml`.** Never `strings.xml` — that is
  upstream's file and it is regenerated. `strings_na.xml` and `strings_neko.xml`
  belong to earlier forks; leave them alone unless you are changing one of their
  features.
- **Reuse an existing drawable or string** where one fits. Telegram ships a very
  large icon set; a new asset should be the exception you can justify.
- **Don't touch translations.** Only the default `values/` locale is authored
  here.
- **Match the surrounding naming.** Look at the neighbouring entries in
  `strings_nax.xml` before inventing a key.
- **Layouts are rare.** This codebase builds its UI in Java/Kotlin, not XML. If
  you are reaching for a new layout file, check how the nearest comparable
  screen is built first — it almost certainly is not.

Full rules: `AGENTS.md` and the `repo-workflow` skill.
