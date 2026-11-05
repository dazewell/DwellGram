---
description: "Interaction design for NagramX: where a control lives, its name, default, off state and every edge, plus the before/after table. Writes no code."
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

Your instructions live in `.github/agents/nagramx-ux.agent.md`. Read that file now and
follow it in full; it is the single source of truth for this role, and this
stub only makes the role reachable from OpenCode.
