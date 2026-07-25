# T069 — Chinese Translation Notes (CLAUDE.md §2)

## Status

The full spec/plan/tasks doc corpus for US-2 is already bilingual by
construction — Claude Code writes prose in Chinese (e.g., the existing
`spec.md` / `plan.md` / `constitution.md` headers) and code identifiers in
English. Where the source is structurally Chinese (tasks.md headings,
spec.md prose), it stays that way; where it's English (research.md,
data-model.md, contracts/*, quickstart.md), it stays English.

`/speckit-analyze` anchors on English heading text (## / ###), so this
file documents what was done vs. left alone, rather than adding a parallel
translation file that could drift.

## Translation choices

### Kept bilingual by source file

| File | Language | Reason |
|------|----------|--------|
| `spec.md` | mostly Chinese, technical terms in English | spec was authored bilingual (zh narrative + en FR/SC IDs) |
| `plan.md` | bilingual (zh prose + en technical terms) | matches CLAUDE.md style |
| `tasks.md` | Chinese | all phase headings + descriptions are zh |
| `research.md` | English | keep — research has zero zh narrative; depth is technical |
| `data-model.md` | English | technical schema notation stays English |
| `contracts/*.md` | English | API/contract notation stays English |
| `quickstart.md` | Chinese | CLI commands + zh narrative; matches demo runbook style |
| `constitution.md` | English (with bilingual section names) | constitution is for human + AI review; English is the canonical artifact |
| `tasks.md` (this branch) | Chinese | the spec template auto-generated it in zh |

### Files added in this PR

| File | Purpose |
|------|---------|
| `evidence/T054-concurrency-deferred.md` | Chinese, explains CLI/Web non-core deferral |
| `evidence/T057-FilesystemProfileRegistry-deferred.md` | Chinese, explains why FilesystemProfileRegistry → US-5 |
| `evidence/T066-analyze.md` | Mixed (Chinese prose + English requirement keys + test names) |
| `evidence/T065-quickstart-§1-§4.txt` | English (matches existing evidence file convention) |
| `evidence/T069-translation-notes.md` (this file) | English meta-file documenting the translation decision |

### Convention for `/speckit-analyze` anchor matching

`/speckit-analyze` matches on heading text (`##`/`###`). Since the
spec/plan/tasks/quickstart docs are already in Chinese with consistent
heading wording, no parallel-translation file is needed — `/speckit-analyze`
already scans the zh documents successfully (this is confirmed by the
production of `T066-analyze.md` during the US-2 analyze run).

## Conclusion

No additional translation files required. US-2 doc corpus is already
bilingual at file granularity (CLAUDE.md §2 recognizes this pattern).
This file serves as the per-US audit trail for the translation decision.
