# ADR 0007: Themes per catalog — a default theme and a catalog list of presets

- **Status:** Accepted
- **Date:** 2026-10-20
- **Scope:** portable catalog contract (`contracts/catalog`: catalog manifest, theme resource, template
  model, rendering semantics) and API contract (`contracts/api`), contract 2.0
- **Related:** [ADR 0002](0002-intermediate-releases.md) (catalog versions),
  [ADR 0003](0003-catalog-identity-and-distribution.md) (catalog dependencies),
  [ADR 0006](0006-stencil-instances-by-reference.md) (stencils by reference)

## Context

A **theme** is a catalog resource holding document styles (font, size, colour, line height), page
settings (paper, orientation, margins, background), named **block style presets** that blocks refer
to by name (`stylePreset`), and a spacing unit. It holds no logo, letterhead or header and footer:
those are stencils and images, and fonts are their own resources.

Exactly one theme styles a render, chosen in this order: the variant's own theme (`themeRef`
override), the template's theme, then a **tenant-wide default theme**. Themes are never merged; the
template's own document styles go on top of the chosen one, and whatever the theme leaves unset falls
to the suite's versioned rendering defaults, which every published version records.

Three things do not fit the model of catalogs as self-contained, versioned units:

1. **The tenant default theme is a cross-catalog assumption.** It lives on the tenant, outside every
   catalog version, and names a theme in some catalog — by default the `default` catalog, which a
   tenant must then keep. A published version does not record it, so what an inheriting template
   looks like depends on tenant state rather than on the version.
2. **Nobody can tell which presets exist.** A block names a preset; whether that name means
   anything depends on whichever theme happens to render it. The editor cannot offer a list, and a
   theme lacking the name renders the block unstyled without a word.
3. **Stencils make (2) worse.** Stencils are the unit of reuse across catalogs (ADR 0006). A stencil
   has no theme: it renders with the theme of the template that uses it. A stencil from a shared
   catalog that uses a preset relies on every using template's theme having it.

## Decision

### 1. A catalog has an optional default theme

The catalog manifest gains `defaultTheme`: a theme reference, in the catalog itself or in a catalog
it depends on, qualified the way other cross-catalog references are. It is part of the catalog's
content, so a published version carries it and renders with it, pinned like any other reference.

A render chooses its theme in this order:

1. the variant's own theme (`themeRef` override);
2. the template's theme;
3. the catalog's `defaultTheme`;
4. none: the rendering defaults alone.

There is no tenant-wide default theme. Nothing in a render depends on any catalog other than the
template's and those its version pins; in particular nothing assumes a catalog named `default`
exists. The system catalog's `default` theme is a theme like any other, chosen explicitly where
wanted.

### 2. A catalog declares its presets; each theme styles them

The catalog manifest gains `presets`: the block style presets the catalog's content may use, each a
key, a name and a description. A theme's `blockStylePresets` supplies the **styles** for them.

- A catalog's **available presets** are its own plus those of the catalogs it depends on. The
  editor offers exactly that list.
- A block's `stylePreset` must name an available preset; validation refuses anything else.
- A theme that does not style an available preset is reported (a quality finding, not an error):
  blocks using it render with their own styles only.
- A theme may style presets no catalog declares; they are offered nowhere, kept for compatibility.

A preset name keeps one meaning across the catalogs a tenant combines: a key declared by a
dependency is the same preset in the dependent catalog. Declaring the same key twice in one
catalog's available list (its own and a dependency's) is refused.

### 3. Stencils from other catalogs

A stencil uses the presets available in its own catalog. Rendered inside a template of another
catalog, it is styled by that template's theme. Because a catalog's available presets include its
dependencies', the dependent catalog's themes are expected to style the stencil's presets too, and
the report in decision 2 says when one does not.

## Room left for later (not decided here)

- **Rebranding an installed catalog.** Templates and stencils that inherit their theme follow the
  catalog's default theme. An installation may later supply its own theme for that slot, recorded
  with the installation so renders stay reproducible, without changing any template.
- **Several brands.** Several themes styling the same declared presets is exactly what a brand
  switch needs; a variant attribute or an installation could later choose between them.
- **Theme inheritance.** Every theme field stays optional, so a "based on" theme that overrides only
  what differs can be added without breaking existing themes.
- **Following a newer brand.** A dependent catalog's published version keeps the theme version it
  pinned; picking up a newer one is a deliberate publish. A notice that a newer version of a
  dependency is available is a suite feature, not a contract change.

## Consequences

- Wire v9 (or the next catalog wire version): `defaultTheme` and `presets` on the catalog manifest;
  `stylePreset` described as a reference to an available preset. Older archives carry neither and
  read as "no default theme" and "no declared presets"; until a catalog declares presets, the editor
  falls back to the presets its themes style, so nothing breaks on upgrade.
- The API exposes and sets both on a catalog version; the tenant default theme endpoints and fields
  are withdrawn.
- **Migration (suite):** every authored catalog with templates that inherit their theme gets the
  tenant's current default theme as its `defaultTheme`, so nothing renders differently; then the
  tenant default theme goes. Each catalog's `presets` are seeded from the preset keys its themes
  style.
- Catalog deletion no longer treats "the tenant's default theme" as a use; the catalog that holds a
  theme some other catalog's `defaultTheme` names is in use through that dependency, as for any
  reference.
