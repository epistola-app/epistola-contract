---
name: release
description: Create a new release. Use when the user wants to release a new version, cut a release, or publish a version.
---

Create a new GitHub release for epistola-contract.

## Prerequisites

- You must be on the `main` branch with a clean working tree
- All changes must be merged to `main`

## Steps

### 1. Determine the next version

Get the latest tag and the commits since it:

```bash
git fetch --tags
LATEST_TAG=$(git tag --sort=-v:refname | head -1)
echo "Latest: $LATEST_TAG"
git log "$LATEST_TAG"..HEAD --oneline
```

The version is derived from `epistola-api.yaml` (`info.version`) which defines the MAJOR.MINOR. The patch number is auto-incremented based on existing git tags.

Apply semantic versioning:

- API-breaking changes → bump **MAJOR** or **MINOR** in `epistola-api.yaml`
- Non-breaking changes → patch is auto-incremented from existing tags

### 2. Prepare the CHANGELOG

Move the `[Unreleased]` section in `CHANGELOG.md` to a new version heading:

```
## [X.Y.Z] - YYYY-MM-DD
```

Add a fresh empty `[Unreleased]` section above it. Commit this change:

```
docs: update changelog for vX.Y.Z release
```

### 3. Ask for confirmation

Before creating the release, show the user:

- The version number
- The commits included
- Ask for permission to proceed

### 4. Push and create the release

```bash
git push origin main
gh release create vX.Y.Z --title "vX.Y.Z" --generate-notes
```

The `--generate-notes` flag auto-generates release notes from PRs and commits.

### 5. Verify

After creating the release, tell the user:

- The release URL
- That CI will automatically build and publish all artifacts (Maven Central, npm, mock server Docker image)
- They can monitor the workflow at: `gh run list --workflow=release.yml --limit 1`

## Important

- Tags must follow the `vX.Y.Z` format (e.g., `v0.1.3`)
- Never skip the CHANGELOG update
- Always ask for confirmation before creating the release
- The CI workflow (`.github/workflows/release.yml`) triggers on `release: published` and handles building, publishing to Maven Central/npm, and mock server Docker image
