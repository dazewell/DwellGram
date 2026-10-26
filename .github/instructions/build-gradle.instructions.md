---
applyTo: "TMessagesProj/build.gradle"
---

# You are editing the app's build file

- **Per-variant values go through `APP_PACKAGE.endsWith('.beta')`** — `.beta` is
  Official (DwellGram), anything else is Unofficial (DwellGramX). That expression
  is the only variant discriminator; `BuildVars.isBetaApp()` returns
  `BuildConfig.DEBUG` in this fork and is **not** a package test.
- **Three ways out of this file.** `manifestPlaceholders` reaches the manifest,
  `buildConfigField` reaches Java, `resValue` reaches resources (that is how
  `@string/NagramX` and the account/mime types are built). Pick by destination,
  and if two of them express the same decision, say so in a comment on each so
  they stay in step.
- **`sourceSets.main.res.srcDirs` picks exactly one icon dir** per variant. The
  comment above that line documents a resource-qualifier trap; read it before
  adding art to `src/iconOfficial` or `src/iconUnofficial`.
- **Don't touch the signing config** unless that is the change. A wrong key
  builds a valid APK no existing install can update to.

Full rules: `AGENTS.md` and the `nagramx-workflow` skill.
