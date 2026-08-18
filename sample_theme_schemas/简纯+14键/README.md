# 简纯+14键 — self-contained IME package

This directory is a flat, self-contained IME package following the same
structure as `Default.zip`.

## Files

- `manifest.yaml` — the single package manifest
  - component composition referencing root definition files
  - `schema_id`, `default_keyboard`, and `rime_files`
- `keyboard.yaml` — all `preset_keyboards`
- `behavior.yaml` — all `preset_keys`
- `color.yaml` — `preset_color_schemes` + `fallback_colors`
- `style.yaml` — `style`
- `liquid_keyboard.yaml` — `liquid_keyboard`
- `14jian.schema.yaml` — full Rime schema

The Rime files themselves are not stored in this directory; `script/package_schema.py`
copies them from `../rime.雾凇` (or `--rime-source`) when generating
`简纯+14键.zip`.

Validation:

```bash
python3 script/validate-definitions.py "sample_theme_schemas/简纯+14键/manifest.yaml"
```
