# Repository Rules for AI Assistants

These rules are binding when working in this repository. If a data definition
cannot be interpreted by the code, do not patch the code around the data —
fix the data definition or the schema first.

## Rules

1. **Never use specific literals in code to fix a data definition problem.**
   - Do not hard-code a particular value, key name, or path just to make a
     broken definition load.
   - Data problems belong in the data/schema layer, not in Kotlin/Python logic.

2. **No magic numbers.**
   - Do not introduce unexplained numeric literals (e.g. `0`, `0x00`,
     `4278190080`, `30`) into validation or parsing logic.
   - If a number has meaning, give it a named constant or express it through
     the schema/definition.

3. **If a definition cannot be interpreted by the code, go back to the drawing
   board.**
   - Re-examine the YAML schema and the actual definition data.
   - Fix the schema, the component files, or the generated assets.
   - Only after the data is correct should the code consume it.

4. **Never modify upstream submodules.**
   - `app/src/main/jni/librime` and the other JNI submodules are upstream
     code we consume, not code we own.
   - Do not edit files inside a submodule to work around a problem; solve it
     in our own layer (`app/src/main/jni/librime_jni/` or the app code).
   - This also means: no depending on librime internals — use the public
     `rime_api.h` surface only.
   - If a fix genuinely belongs upstream, report/upstream it separately;
     never carry it in our tree.

5. **Data inconsistency is a data problem — fix the data, not the code.**
   - When the same data is defined inconsistently (e.g. one YAML value
     carries padding spaces while the rest do not) and the code that
     consumes it produces inconsistent behavior, normalize the data to a
     single consistent form instead of loosening the code to tolerate every
     variant.
   - Relaxing the parser/consumer to accept all variants hides the
     inconsistency and makes the schema meaningless; the schema and the
     shipped data must agree, then the code consumes that one form.
   - Apply this also to packaged assets: when the unpacked sources are
     fixed, refresh the corresponding zip/asset so the shipped artifact
     matches the sources.

## Working style

- Validate definitions early and fail loudly with clear messages.
- Prefer schema-first validation over defensive code.
- Keep generated assets consistent with their source schemas.
- Do not push unverified or unfinished work to remote.

## Git push target

Development pushes go to **`origin_home`** (the maintainer's own Gitea
server, `http://192.168.1.50:3000/Home/trime`), **not** to `origin`
(GitHub `FlyingpigNZ/trime`) or `upstream` (GitHub `osfans/trime`).

- New work on the current feature branch should be pushed with an explicit
  remote: `git push origin_home <branch>`.
- After pushing, set the upstream so plain `git push` uses the right target:
  `git branch --set-upstream-to=origin_home/<branch>`.
- Do **not** push feature branches to `origin` / `upstream` by default. Those
  are only for upstream-fork work (e.g. a PR to osfans/trime), which the
  maintainer handles explicitly.

### Creating a PR (Gitea)

Use the `tea` CLI (Gitea's CLI), not `gh` (GitHub). `tea` is already
authenticated to `http://192.168.1.50:3000`.

```bash
tea pull create --repo Home/trime --base main --head <branch> \
  --title "..." --description "..."
```

- `--repo Home/trime` is the Gitea repo slug (owner `Home`, name `trime`).
- Make sure the branch is pushed to `origin_home` first (PRs read the Gitea
  server's copy of the branch).
- After creating it, open the returned URL (or `tea pull list --repo Home/trime`)
  to verify.
