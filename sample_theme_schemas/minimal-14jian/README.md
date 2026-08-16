# Minimal 14键 schema + layout reference

This directory is the canonical tier-3 reference for pairing a Rime schema with
its custom keyboard layout(s).

## Files

- `manifest.yaml` — package manifest: schema id, schema file, layout file(s),
  optional default keyboard.
- `14jian.schema.yaml` — minimal Rime schema, stripped of rime-ice/Lua extras.
- `14jian.layout.yaml` — minimal layout fragment, stripped of theme decoration
  and the sample's `conf`/`__patch` machinery.

## How to read it

The full working examples live one level up:

- `sample_theme_schemas/14jian.schema.yaml`
- `sample_theme_schemas/简纯+14键.trime.yaml`

The minimal pair keeps only what a customer schema/layout needs:

- schema: `schema`, `switches`, `engine`, `speller`, `translator`
- layout: `preset_keys`, `preset_keyboards`, and explicit `standard_*`
  declarations for tier-1 components

The layout file is intentionally valid as a standalone minimal theme (it has
`style: {}`), but at runtime the schema-tier loader will merge it on top of a
decoration theme instead of shipping a full theme.
