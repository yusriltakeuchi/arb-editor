<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# ARB Editor Changelog

## [1.0.1] - 2026-03-30

### Update

- Update readme

## [1.0.0] - 2026-03-27

### Added

- **Placeholder Validation** — Detect missing or extra `{placeholder}` tokens across languages. Cells with issues are highlighted in orange with detailed tooltips. Prevents Flutter runtime crashes at edit-time.
- **Import CSV** — Import translations from a CSV file with a merge preview dialog. Supports adding new keys and new languages automatically.
- **Translation Progress Bars** — Color-coded completion bars in each language column header (green ≥100%, yellow ≥70%, red <70%).
- **Key Rename / Refactor** — Rename a translation key across all ARB files and `@key` metadata via right-click context menu.
- **Duplicate Value Detection** — Scan a language for keys sharing identical translations to catch copy-paste errors or consolidation opportunities.
- **Key Grouping / Prefix Filter** — Filter keys by auto-detected prefix groups (`home_`, `auth_`, `settings_`, etc.) via dropdown combo box.

### Changed

- Reorganized toolbar into a clean two-row layout: core actions on row 1, search + prefix filter on row 2.
- Less-frequent tools (Export CSV, Import CSV, Fill from Reference, Validate Placeholders, Find Duplicates) grouped into a **Tools ▾** dropdown popup for a cleaner UI.
- Search field now uses `BorderLayout.CENTER` and stretches to fill all available width.
- Translation cell renderer upgraded to show placeholder validation warnings (orange background + border + tooltip).
- Language header renderer upgraded to inner class with access to model for progress bar calculation.
