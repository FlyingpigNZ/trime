# 简纯+14键 — full three-tier split

This directory is the full conversion of the legacy monolith
`sample_theme_schemas/简纯+14键.trime.yaml` into the new three-tier model.

## Files

- `theme.yaml` — Tier 2 decoration theme
  - `name`, `author`, `style`, `fallback_colors`, `liquid_keyboard`
  - all four color schemes converted to self-contained `light:`/`dark:` pairs
  - general keyboards: `default`, `letter`, `number`, `edit`, `func`,
    `symbols`, the `sym*`/`emoji*`/`ywz*` pages, and `symbolscn`
  - general/shared preset keys
- `manifest.yaml` — Tier 3 package manifest
- `14jian.schema.yaml` — full Rime schema (copied from the sample pair)
- `14jian.layout.yaml` — Tier 3 schema layout fragment
  - schema-specific keyboards: `14jian`, `letter_14jian`, `14number`,
    `14numberen`, `14symbols`, `14symbolsen`, `letter_18jian`
  - schema-specific + shared non-standard preset keys needed to make the
    layout self-contained when merged with the standard catalog

## How it was produced

`script/split_legacy_theme.py` reads the legacy monolith, expands the old
`conf`/`styl` indirection and `__patch` operations into explicit fields, then
partitions the definitions into the two files above.

Validation:

```bash
python3 script/validate-definitions.py "sample_theme_schemas/简纯+14键/theme.yaml" --kind theme
python3 script/validate-definitions.py "sample_theme_schemas/简纯+14键/14jian.layout.yaml" --kind layout
python3 script/validate-definitions.py "sample_theme_schemas/简纯+14键/manifest.yaml" --kind manifest
```
