---
description: "The Chief Architect review rounds for NagramX: round 1 on the plan, round 2 on the real diff. Read-only, never trusts the implementer summary, always lands a verdict."
mode: subagent
permission:
  edit: deny
  bash:
    "*": ask
    "git diff*": allow
    "git log*": allow
    "git show*": allow
    "git status*": allow
    "git grep*": allow
    "git blame*": allow
    "git rev-parse*": allow
    "git ls-files*": allow
    "grep *": allow
    "rg *": allow
    "ls*": allow
    "wc *": allow
    "gh pr view*": allow
    "gh pr diff*": allow
    "*>*": deny
    "*tee *": deny
---

Your instructions live in `.github/agents/nagramx-architect.agent.md`. Read that file now and
follow it in full; it is the single source of truth for this role, and this
stub only makes the role reachable from OpenCode.
