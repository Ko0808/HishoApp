# AI diagnostics — 0.32.1

最終確認: ユーザーがAPI残高不足を確認して入金したと報告。入金後の通常処理で「判定成功: ignore」の記録を確認できた。原因はAPI残高不足。以前の確認待ちは自動再投入しないため、明示的な再判定が必要。

Resolution: The user confirmed insufficient API credit and added funds. A successful normal classification (ignore) was observed afterward. Existing review items still require explicit reclassification.

2026-09-04: Pixel 7aで保存済みキー・本文送信同意を使い、架空通知1件で接続テスト。HTTP 429を再現。その後18:36:10に通常処理の「判定成功: ignore」を確認。継続的な残高不足とは断定できず、一時的な利用制限も考えられる。元の6件すべてが同じHTTPコードだったかは旧記録からは確認できない。診断操作では既存確認待ちを再投入せず、Google Tasksを作成していない。

The synthetic on-device request returned HTTP 429. A subsequent normal classification succeeded with verdict ignore at 18:36:10. Persistent exhausted credit cannot be concluded; a transient limit remains possible. Historical generic errors cannot establish the original HTTP codes. No review items were requeued or Google tasks created by diagnostic actions.

- Persist only timestamp, verdict, fixed failure descriptions, HTTP status and allowlisted codes. Never persist raw server errors, keys, notification content, or exception messages in diagnostics.
- AI settings observes status changes while visible.
- Failed classifications remain in review; no automatic approval or repeated paid retries.
- Connection test uses the same classifier/model/schema as normal processing, bypasses Google sync, and requires saved key/content consent.
- Official reference: https://developers.openai.com/api/docs/guides/error-codes
