# Agent skills for NativePath users

Skills for coding agents (Claude Code and compatible) that install NativePath, move an existing
application onto it, and switch between its variants. Copy a skill folder into your project's
`.claude/skills/` (or your user-level skills folder).

| Skill | Use it to |
|---|---|
| `np-install` | Get the native library and jars for your platform and wire them into a project |
| `np-migrate-app` | Adopt NativePath in an existing application, one verified step at a time |
| `np-switch-variant` | Change which variant is on (speed only, secret mode, server, MVC preview, database) or back out |

They follow the documentation of the release you installed (`docs/<version>/`): how to use, configure,
migrate and verify. Skills are versioned with the release; use the ones from the bundle you installed.
