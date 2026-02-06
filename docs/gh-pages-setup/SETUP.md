# GitHub Pages Setup Instructions

This directory contains the initial files for the `gh-pages` branch. Follow these steps to set up GitHub Pages for API documentation.

## Setup Steps

### 1. Create the gh-pages branch

```bash
# From the main branch, create an orphan gh-pages branch
git checkout --orphan gh-pages
git reset --hard

# Copy the setup files
cp docs/gh-pages-setup/index.html .
cp docs/gh-pages-setup/versions.json .
cp docs/gh-pages-setup/.nojekyll .

# Commit and push
git add index.html versions.json .nojekyll
git commit -m "docs: initialize GitHub Pages for API documentation"
git push -u origin gh-pages

# Return to main branch
git checkout main
```

### 2. Enable GitHub Pages

1. Go to repository **Settings** > **Pages**
2. Under "Source", select **Deploy from a branch**
3. Select branch: **gh-pages** / **/ (root)**
4. Click **Save**

### 3. Deploy the first version

Either:
- **Wait for a release**: The docs will automatically deploy after a successful release to Maven Central
- **Manual deployment**: Go to **Actions** > **Deploy API Documentation** > **Run workflow**, enter the version (e.g., "1.0")

## What Gets Deployed

After deployment, your documentation site will have:

```
https://sdegroot.github.io/epistola-contract/
├── index.html          # Landing page with version cards
├── versions.json       # Version manifest
├── latest/             # Always points to newest version
├── v1.0/               # Version 1.0 docs
│   ├── index.html      # Redoc documentation page
│   └── openapi.yaml    # OpenAPI spec
└── v1.1/               # Version 1.1 docs (after next release)
```

## Files in This Directory

- **index.html** - Landing page that lists all available API versions
- **versions.json** - Empty manifest (populated by the workflow)
- **.nojekyll** - Prevents GitHub from processing files with Jekyll

## Cleanup

After setting up the gh-pages branch, you can optionally delete this setup directory:

```bash
git rm -r docs/gh-pages-setup
git commit -m "chore: remove gh-pages setup files"
```
