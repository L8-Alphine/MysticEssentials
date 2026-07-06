---
title: Wiki Import Notes
description: Notes for publishing these Markdown pages to wiki.hytalemodding.dev.
---

# Wiki Import Notes

These pages were written as portable Markdown for the Mystic Essentials section of `wiki.hytalemodding.dev`.

## Suggested route structure

```text
mysticessentials/
  index.md
  getting-started.md
  guides/
    player-guide.md
    admin-guide.md
  commands.md
  permissions.md
  configuration.md
  integrations-and-storage.md
  chat-formatting.md
  developer-api.md
  troubleshooting.md
  features/
    teleport-spawn-homes-warps.md
    chat-mail-social.md
    kits-flight-inventory-nicknames.md
```

In this repository, `wiki/README.md` is the section landing page. Rename it to `index.md` if the wiki framework expects index files.

## Sidebar

`wiki/_sidebar.md` is included for docsify-style or manual sidebar import. If the HytaleModding wiki uses a different navigation system, translate the same order into that system's sidebar config.

## Front matter

Each page uses simple YAML front matter:

```yaml
---
title: Page Title
description: Short page summary.
---
```

If the target framework does not use front matter, it can be removed without affecting page content.

## Content source of truth

The docs are based on:

- `README.md`
- `PERMISSIONS.md`
- `DEVELOPER_NOTES.md`
- `src/main/resources/manifest.json`
- Module config classes in `src/main/java/org/hyzionstudios/mysticessentials/modules/**`
- Public API interfaces in `src/main/java/org/hyzionstudios/mysticessentials/api/**`

When the mod changes, update command tables, config defaults, and permission nodes from source before publishing.
