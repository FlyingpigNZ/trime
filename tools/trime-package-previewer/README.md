# Trime YAML Attribute Reference & Package Previewer

A standalone HTML application that documents the YAML attributes consumed by the
Trime Android app UI and previews a self-contained IME package as an on-screen
keyboard.

## Usage

Open `index.html` in a browser (no build step).

- **Attribute Reference tab** lists the supported YAML attributes, categorized
  by concern:
  - Package & manifest composition
  - Keyboard layouts and per-key fields
  - Preset key behaviors
  - Style / general appearance
  - Preedit / candidate window / toolbar
  - Color system
  - Liquid keyboard
- **Package Preview tab** loads a package:
  - **Load package folder** — select the unzipped package directory containing
    `manifest.yaml` (Chromium-based browsers support folder selection).
  - **Load package zip** — select a `.zip` package (uses JSZip from CDN).

The previewer resolves `components`, merges section data, resolves
`__include`, color palettes and fallbacks, then renders the default keyboard
(or the keyboard selected in the dropdown) with the chosen color scheme and
light/dark mode. Keys are clickable: pressing a key highlights it with the
hilited color and appends the clicked key to the candidate/preedit area.

## Notes

- The app loads `js-yaml` and `JSZip` from jsDelivr CDN. An internet connection
  is required unless you vendor those libraries locally.
- Rime-engine YAML attributes (`schema`, `dict`, `lua`, `opencc`, etc.) are
  intentionally not documented here.
- The preview is an approximation of the Android rendering; exact font metrics,
  popups, liquid keyboard, and system insets are not simulated.
