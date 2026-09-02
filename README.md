# Hisho / 秘書

Android通知から行動候補を抽出し、Google TasksとGoogle Calendarへ同期するローカル優先のタスクスケジューラーです。

A local-first Android task scheduler that turns notifications into actionable Google Tasks and schedules them in available Google Calendar time blocks.

**Current version / 現在のバージョン:** `0.15.0` (`versionCode 17`)

---

## 日本語

### 概要

HishoはGmail、Slack、Discord、LINEの通知を取得し、次の処理を行います。

1. 同一通知の更新を重複除外
2. 通知内容をAndroid Keystoreの鍵でAES-GCM暗号化して一時保存
3. タスク候補、期限、工数、優先度、カテゴリをローカルで推定
4. 通知元に応じた短い行動タイトルを生成
5. Google Tasksの専用リスト`Auto Captured Tasks`へ登録
6. Google Calendarの予定を避けて、開始・終了時刻を持つ作業枠を作成
7. 任意で、未完了タスクを次の空き時間へ再配置

現在、通知本文を外部AIへ送信していません。

### 実装済みの機能

- `NotificationListenerService`による通知取得とアプリ別ON／OFF
- SHA-256と時間窓による重複排除
- 通知本文とGoogleアクセストークンの暗号化
- SQLiteの永続キューとWorkManagerによる再試行・15分間隔の同期
- Google Play services `AuthorizationClient`によるOAuth認証
- Google Tasks／Calendar APIとの冪等な同期
- 日本語の相対日付、曜日、日付・時刻表現の解析
- XS／S／M／L／XLの工数、優先度、カテゴリ推定
- Gmail、Slack、Discord、LINEに応じた短いタスク名の生成
- タイトル、期限、優先度、工数の手動編集
- 同期済みタスクの更新とCalendar再配置
- Lを60分×2、XLを60分×4に分割
- 今日の予定、次の予定、締切注意、同期状態の表示
- 稼働時間、余白、1日の上限、土日、昼休みの設定
- 未完了タスクの自動再配置、再計画上限、要確認状態
- 要確認タスクの再開とGoogle Tasksへの完了同期

### 標準のスケジュール設定

- 稼働時間：09:00〜18:00
- 予定間の余白：10分
- 1日の予定上限：6時間
- 土日：配置しない
- 昼休み：12:00〜13:00を避ける
- 長時間タスク：最大60分の枠に分割
- 未完了タスクの自動再配置：OFF
- 再計画上限：3回（1／3／5回から選択）

設定変更は新規または再配置されるタスクから適用され、既存予定を一括変更しません。

### 必要環境とGoogle Cloud

- Android 8.0（API 26）以上
- Google Play services
- 通知へのアクセス権限
- Google Tasks APIとGoogle Calendar APIを有効にしたGoogle Cloudプロジェクト
- パッケージ名`app.hisho`と署名証明書に対応するAndroid OAuthクライアント

要求するOAuthスコープ：

- `https://www.googleapis.com/auth/tasks`
- `https://www.googleapis.com/auth/calendar.events`
- `https://www.googleapis.com/auth/calendar.events.freebusy`

OAuthクライアントIDは`app/build.gradle.kts`の`GOOGLE_ANDROID_CLIENT_ID`で設定します。クライアントIDは公開識別子ですが、本番の秘密情報や署名鍵はコミットしないでください。

### ビルドとインストール

Java 17とAndroid SDKを設定して実行します。

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

APK出力先：`app/build/outputs/apk/debug/app-debug.apk`

### 初回セットアップ

1. Hishoを起動し、「通知へのアクセスを設定」から許可します。
2. 取得対象のアプリを選択します。
3. 「Googleアカウントを接続」からTasksとCalendarへのアクセスを許可します。
4. 通知受信後、「端末内キュー」で同期待ち件数を確認します。
5. 必要なら「タスク推定を確認・修正」で編集します。
6. 「今すぐ同期」を押すか、バックグラウンド同期を待ちます。

### 推奨する実機検証

1. 対応アプリから通知を発生させ、同一通知の更新が重複しないことを確認します。
2. 未同期タスクのタイトル、期限、優先度、工数を編集して同期します。
3. Calendar枠が稼働時間内にあり、既存予定・土日・昼休みを避けることを確認します。
4. Sは25分、Mは60分、Lは2枠、XLは4枠になることを確認します。
5. 同期済みタスクを編集し、以前の枠が整理されて再配置されることを確認します。
6. 自動再配置を有効にし、未完了タスクが予定終了後に移動することを確認します。
7. 再計画上限で「要確認」になり、再開または完了にできることを確認します。

### プライバシーと安全性

- 通知本文は端末内で暗号化し、Logcatへ出力しません。
- 同期成功後、保存していた通知本文を端末内キューから消去します。
- 判定とタイトル生成は端末内ルールで行い、外部AIへ本文を送りません。
- 自動再配置は初期状態でOFFです。
- 同期済みタスクの編集は手動または定期同期時にGoogleへ反映されます。

### 既知の制限

- 個人端末向け検証版で、本番署名とGoogle Play向けOAuth審査は未対応です。
- OAuth同意画面がテスト公開の場合、再認証が必要になることがあります。
- 旧バージョンの予定へ現在の設定は遡及適用されません。
- 旧DBの項目には短縮タイトルが保存されていない場合があります。
- Calendar側で移動・削除した予定の完全な双方向追跡は未実装です。
- 分割された全Calendar枠の永続的な個別追跡は改善途中です。
- 通知形式の違いにより、タイトル・期限・工数の推定が誤る場合があります。
- 祝日、曜日別の個別稼働時間、自由な休憩時間、希望時間帯は未対応です。
- アカウント切断、OAuth権限取り消し、全データ削除UIは未実装です。
- AIによる意味的な要約・推定・タスク分解は未実装です。

### 今後の計画

1. 分割Calendar枠の完全な永続追跡
2. Calendar側の移動・削除をユーザーの意図として取り込む
3. タスクの絞り込み、検索、詳細、削除、一括操作
4. 曜日別稼働時間、祝日、自由な休憩時間、希望時間帯
5. 手動入力、Android共有メニュー、音声入力
6. アカウント切断、OAuth権限取り消し、データ削除
7. DB移行、API、オフライン、大量通知、バッテリー試験
8. 本番署名、プライバシーポリシー、Google Play公開対応
9. 同意と匿名化を前提にした任意のAI支援

---

## English

### Overview

Hisho captures Gmail, Slack, Discord, and LINE notifications, then:

1. Deduplicates repeated notification updates.
2. Temporarily encrypts content with AES-GCM using an Android Keystore-backed key.
3. Locally infers task candidacy, deadline, effort, priority, and category.
4. Generates a concise action title tailored to the source app.
5. Creates an item in the dedicated Google Tasks list, `Auto Captured Tasks`.
6. Finds free time and creates Google Calendar events with explicit start and end times.
7. Optionally reschedules unfinished tasks into later free time.

Notification content is not currently sent to an external AI service.

### Implemented features

- Notification capture with `NotificationListenerService` and per-app toggles
- SHA-256 and time-window deduplication
- Encrypted notification payloads and Google access tokens
- Durable SQLite queue with WorkManager retries and 15-minute periodic sync
- OAuth through Google Play services `AuthorizationClient`
- Idempotent Google Tasks and Calendar API synchronization
- Japanese relative-date, weekday, date, and time parsing
- XS/S/M/L/XL effort, priority, and category inference
- Source-aware titles for Gmail, Slack, Discord, and LINE
- Manual title, deadline, priority, and effort editing
- Synced-task updates and Calendar rescheduling
- Two 60-minute blocks for L and four blocks for XL tasks
- Today, next-task, deadline-risk, and synchronization-status summaries
- Working hours, buffers, daily capacity, weekend, and lunch settings
- Optional unfinished-task recovery with configurable limits
- Restart and Google Tasks completion actions for items needing attention

### Default scheduling settings

- Working hours: 09:00–18:00
- Event buffer: 10 minutes
- Daily capacity: 6 hours
- Weekends: disabled
- Lunch break: avoid 12:00–13:00
- Long tasks: split into blocks of at most 60 minutes
- Automatic unfinished-task recovery: disabled
- Recovery limit: 3 attempts, configurable to 1, 3, or 5

Changes apply to new or explicitly rescheduled tasks and do not bulk-edit existing events.

### Requirements and Google Cloud

- Android 8.0 (API 26) or later
- Google Play services and notification access
- A Google Cloud project with the Google Tasks and Google Calendar APIs enabled
- An Android OAuth client matching package `app.hisho` and the signing certificate

Required OAuth scopes:

- `https://www.googleapis.com/auth/tasks`
- `https://www.googleapis.com/auth/calendar.events`
- `https://www.googleapis.com/auth/calendar.events.freebusy`

Set the public OAuth client ID through `GOOGLE_ANDROID_CLIENT_ID` in `app/build.gradle.kts`. Never commit production secrets or signing keys.

### Build and install

Configure Java 17 and the Android SDK, then run:

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`

### First-time setup

1. Open Hisho and grant notification access.
2. Select the apps to capture.
3. Connect a Google account and grant Tasks and Calendar access.
4. Generate a notification and check the pending queue count.
5. Optionally review and edit the inferred task.
6. Select **Sync now** or wait for background synchronization.

### Recommended device validation

1. Generate supported-app notifications and verify duplicate updates are ignored.
2. Edit a pending task's title, deadline, priority, and effort, then sync it.
3. Verify Calendar blocks stay within working hours and avoid events, weekends, and lunch.
4. Verify S uses 25 minutes, M uses 60 minutes, L creates two blocks, and XL creates four.
5. Edit a synced task and verify its prior blocks are replaced by a new schedule.
6. Enable recovery and verify an unfinished task moves after its scheduled end.
7. Verify a task exceeding its limit becomes **Needs attention** and can be restarted or completed.

### Privacy and safety

- Notification content is encrypted at rest and never written to Logcat.
- Stored payloads are scrubbed after successful synchronization.
- Classification and title generation run locally without external AI.
- Automatic recovery is disabled by default.
- Synced-task edits reach Google only during a manual or periodic sync.

### Known limitations

- This is a personal-device validation build; production signing and Google Play OAuth review are incomplete.
- Testing-mode OAuth may require reauthorization.
- Current preferences are not applied retroactively to old events.
- Migrated entries may not have a stored concise title.
- Full two-way reconciliation of Calendar-side moves and deletions is not implemented.
- Durable per-block tracking for every split event is still being improved.
- Notification-format differences can cause incorrect title, deadline, or effort inference.
- Public holidays, per-weekday hours, custom breaks, and preferred time windows are unsupported.
- Account disconnect, OAuth revocation, and full data-deletion controls are not implemented.
- AI-assisted semantic summarization, estimation, and decomposition are not implemented.

### Roadmap

1. Persist and reconcile every split Calendar block.
2. Import Calendar-side moves and deletions without overwriting user intent.
3. Add task filters, search, details, deletion, and bulk actions.
4. Add per-weekday hours, public holidays, custom breaks, and preferred time windows.
5. Add manual entry, Android sharing, and voice capture.
6. Add account disconnect, OAuth revocation, and data-deletion controls.
7. Expand migration, API, offline, load, and battery testing.
8. Add production signing, privacy documentation, and Google Play release readiness.
9. Optionally add consent-based, privacy-preserving AI assistance.
