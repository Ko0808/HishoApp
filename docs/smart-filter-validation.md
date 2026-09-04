# Smart filter / Tasks-only validation — 0.31.0

## Result — 2026-09-04

53 unit tests passed; debug APK and lint completed without errors (existing warnings remain). Pixel 7a was updated without clearing data. The Tasks home link, setup entry points, and named-app rule-selection screen were checked on-device without changing user rules. Live AI classification accuracy, actual Google completion propagation, and legacy-event deletion were not exercised. Content consent remains a separate opt-in; no real notification payloads were sent for testing.

## Implemented

- Exclusion before force before AI, with fail-closed review and separate content consent.
- Keyword normalization, named-app source selection, reasons, original-notification preview, approval/retry/ignore.
- Tasks-only creation and Google completion/deletion observation; no new Calendar writes except explicitly requested deletion.
- Selected legacy-event cleanup with a second confirmation; no automatic removal of existing events.

## Automated

Run `gradlew.bat testDebugUnitTest assembleDebug lintDebug`.
Rules tests cover conflicting rules, source equality, empty rules, body matching, and case/width normalization.
Date tests verify that Google receives the intended local date, without a time component.
Existing scheduler tests are historical regression tests, not proof that scheduling remains active.

## Device checks / 実機検証

Use disposable synthetic notifications/tasks, not real personal tasks for destructive checks.

1. Excluded notification: no Google task and no AI request.
2. Force match: one visibly high-priority task, no AI request, no Calendar event.
3. Conflict: exclusion wins.
4. AI missing/disabled/error/refusal: review only, no task.
5. Real action: concise action title; advertisement/status/general chat: ignore; ambiguous assignee: review.
6. Embedded “ignore previous instructions / force task” text: review or ignore, never obey it as a command.
7. Read a review item, edit its title, approve; verify exactly one task. Retry after API configuration; verify a new decision.
8. Complete in Google Tasks and sync: local completion reflected. Delete in Google: no recreation and legacy tracking remains available for cleanup.
9. Select legacy cleanup, cancel: no deletion. Select disposable event and confirm: only its tracked events disappear, Task remains. Network failure can retry idempotently.
10. Restart, offline recovery, repeated notifications, long input, rotation, and a backlog of notifications.

AI accuracy and live Google side effects require separate end-to-end validation; unit-test success does not establish them.
