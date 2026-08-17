# 简纯+14键 — self-contained IME package

This directory is the conversion of the legacy monolith
`sample_theme_schemas/简纯+14键.trime.yaml` into a flat, self-contained
package following the same structure as `Default.zip`.

## Files

- `manifest.yaml` — the single package manifest
  - component composition referencing root definition files
  - `schema_id`, `default_keyboard`, and `rime_files`
- `keyboard.yaml` — all `preset_keyboards` from the original monolith
- `behavior.yaml` — all `preset_keys` from the original monolith
- `color.yaml` — the monolith's `preset_color_schemes` + `fallback_colors`
- `style.yaml` — the monolith's `style`
- `liquid_keyboard.yaml` — the monolith's `liquid_keyboard`
- `14jian.schema.yaml` — full Rime schema

The Rime files themselves are not stored in this directory; `script/package_schema.py`
copies them from `../rime.雾凇` (or `--rime-source`) when generating
`简纯+14键.zip`.

## How it was produced

`script/split_legacy_theme.py` reads the legacy monolith, expands the old
`conf`/`styl` indirection and `__patch` operations into explicit fields, then
partitions the definitions into the split files above.

Validation:

```bash
python3 script/validate-definitions.py "sample_theme_schemas/简纯+14键/manifest.yaml" --kind manifest
```
