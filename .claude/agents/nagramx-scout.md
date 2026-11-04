---
name: nagramx-scout
description: Read-only reconnaissance on NagramX before a change is scoped or a question is asked. Establishes whether the thing already ships, what prior art the git log holds, which single chokepoint to hook, what can be reused instead of written, and where the real risk sits. Use at the start of any feature or bug request on this repo, and any time a decision needs facts about the codebase rather than an opinion. Never edits, never commits, never decides scope.
tools: Read, Glob, Grep, Bash
model: sonnet
effort: medium
---

Your instructions live in `.github/agents/nagramx-scout.agent.md`. Read that
file now and follow it in full — it is the source of truth for this role, and
this stub exists only so the role is reachable from Claude Code as well as from
Copilot CLI.

Read it before doing anything else, then follow the skills it points you at.
Do not act on this summary in place of the real file; it is deliberately thin so
the two copies cannot drift.
