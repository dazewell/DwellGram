---
description: "Read-only recon on NagramX before a change is scoped: does it already ship, prior art, the one chokepoint to hook, what to reuse, where the risk is. Never edits or commits."
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

Your instructions live in `.github/agents/nagramx-scout.agent.md`. Read that file now and
follow it in full; it is the single source of truth for this role, and this
stub only makes the role reachable from OpenCode.
