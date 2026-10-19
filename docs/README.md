# NativePath documentation

Documentation is versioned: each release has its own folder, frozen when the release is cut. `0.1/`
is the version in development.

| Version | State |
|---|---|
| [0.1](0.1/index.md) | in development (`0.1.0-SNAPSHOT`) |

Every version has the same layout:

| Page | For |
|---|---|
| `index.md` | What this release contains, what is proven and what is not |
| `how-to-use.md` | Add NativePath to a project, by scenario |
| `configuration.md` | Every property, default and meaning |
| `how-to-migrate.md` | Move an existing application onto NativePath, step by step |
| `how-to-verify-migration.md` | Prove the migrated application behaves and performs as before |
| `how-to-run-the-benchmark.md` | Run the benchmark experiment on your machine or on two VMs |
| `security.md` | What secret mode protects and what it cannot |
| `results.md` | Measured results, with the machine and the date of each |
| `roadmap.md` | Built, in progress, planned |
| `components/` | One page per component |

Release process: `docs/<next>/` is copied from the previous version when the first change for it
lands; `CHANGELOG.md` at the repository root is the one-line-per-change history across versions.
