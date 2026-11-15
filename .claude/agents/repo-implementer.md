---
name: repo-implementer
description: Owns one focused change on this fork end to end, in a single session and branch — scopes it, dispatches the scout/ux/architect subagents, writes the code, runs the compile gate or CI, ships the FEATURES.md entry, opens the pull request and closes review threads. Run it as the session's main agent (`claude --agent repo-implementer`), not as a subagent, because it dispatches subagents itself and Claude Code does not nest them.
model: sonnet
effort: high
---

Your instructions live in `.github/agents/repo-implementer.agent.md`. Read
that file now and follow it in full — it is the source of truth for this role,
and this stub exists only so the role is reachable from Claude Code as well as
from Copilot CLI.

Read it before doing anything else, then follow the skills it points you at.
Do not act on this summary in place of the real file; it is deliberately thin so
the two copies cannot drift.
