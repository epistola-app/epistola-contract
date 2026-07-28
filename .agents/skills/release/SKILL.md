---
name: release
description: Create a new release. Use when the user wants to release a new version, cut a release, or publish a version.
---

Create a new GitHub release for `epistola-contract`.

## Prerequisites

- You must be on the `main` branch with a clean working tree.
- All changes must be merged to `main`.
- Never push to a remote or create the GitHub release without explicit user permission.

## Steps

### 1. Determine the next version

Get the latest tag and the commits since it:

```bash
git fetch --tags
LATEST_TAG=$(git tag --sort=-v:refname | head -1)
echo "Latest: $LATEST_TAG"
git log "$LATEST_TAG"..HEAD --oneline
```

The version is derived from `contracts/api/openapi.yaml` (`info.version`) which defines the `MAJOR.MINOR`.
The patch number is auto-incremented based on existing git tags.

Apply semantic versioning:

- API-breaking changes: bump **MAJOR** or **MINOR** in `contracts/api/openapi.yaml`.
- Non-breaking changes: patch is auto-incremented from existing tags.

### 2. Prepare the changelog

Move the `[Unreleased]` section in `CHANGELOG.md` to a new version heading:

```markdown
## [X.Y.Z] - YYYY-MM-DD
```

Add a fresh empty `[Unreleased]` section above it. Commit this change:

```text
docs: update changelog for vX.Y.Z release
```

### 3. Update the spec version

Update `info.version` in `contracts/api/openapi.yaml` to the full release version, for example `0.3.1`.
This ensures the spec always reflects the exact release version, which is baked into the clients'
`User-Agent` header (`epistola-contract/X.Y.Z`).

Commit this change:

```text
release: bump spec version to X.Y.Z
```

### 4. Ask for confirmation

Before pushing or creating the release, show the user:

- The version number.
- The commits included.
- The local commits that will be pushed, if any.
- The exact release command that will run.

Ask for explicit permission to proceed.

### 5. Push and create the release

Only after the user confirms:

```bash
git push origin main
gh release create vX.Y.Z --title "vX.Y.Z" --generate-notes
```

The `--generate-notes` flag auto-generates release notes from PRs and commits.

### 6. Verify

After creating the release, tell the user:

- The release URL.
- That CI will automatically build and publish all artifacts: Maven Central, npm, NuGet, PyPI,
  and the mock server Docker image.
- They can monitor the workflow with:

```bash
gh run list --workflow=release.yml --limit 1
```

## Important

- Tags must follow the `vX.Y.Z` format, for example `v0.1.3`.
- Never skip the changelog update.
- Always ask for confirmation before pushing or creating the release.
- The CI workflow (`.github/workflows/release.yml`) triggers on `release: published` and handles
  building and publishing release artifacts.
