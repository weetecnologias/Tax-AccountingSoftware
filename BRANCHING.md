# Branching Strategy

| Branch    | Purpose                                              | Merges into |
|-----------|------------------------------------------------------|-------------|
| `main`    | Production only. **Never push or commit to it directly.** | — |
| `develop` | Integration branch. All finished work lands here.    | `main` (at release time) |
| `feature` | New features and enhancements.                       | `develop` |
| `bugfix`  | Bug fixes found during development/testing.          | `develop` |
| `docs`    | Documentation (user guides, specs, READMEs).         | `develop` |

## Rules

1. Start work from the latest `develop`:
   ```
   git checkout develop
   git pull
   git checkout bugfix        # or feature / docs
   git merge develop
   ```
2. Commit on the working branch, push it, then open a Pull Request into `develop`.
3. `main` is updated **only** when a release is ready, by a Pull Request from `develop` into `main`.
4. For larger or parallel tasks, a short-lived branch such as `feature/gst-returns` or
   `bugfix/invoice-total` can be cut from `develop` and merged back the same way.
