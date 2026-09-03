# Hisho / 秘書

Android通知から行動候補を抽出し、Google TasksとGoogle Calendarへ同期するローカル優先のタスクスケジューラーです。

A local-first Android task scheduler that turns notifications into actionable Google Tasks and schedules them in available Google Calendar time blocks.

**Current version / 現在のバージョン:** `0.27.0` (`versionCode 29`)

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
- Hisho内からタイトル、期限、工数、優先度を指定する手動タスク追加
- タイトル、期限、優先度、工数の手動編集
- 同期済みタスクの更新とCalendar再配置
- Lを60分×2、XLを60分×4に分割
- 分割した各Calendar枠のイベントID、順番、時刻、再計画世代を永続追跡
- Calendar側で移動した追跡対象枠の時刻をHishoへ反映し、削除時は要確認として停止
- 次のタスク、自動運転状態、対応が必要な問題だけに絞ったホーム画面
- 締切危険・同期失敗・要確認・同期待ちからタスク確認へ進む導線
- Google Calendarを直接開く操作と、運用項目を集約した設定画面
- タスク状態の絞り込みとタスク名・通知元の検索
- 確認付きのタスク削除とGoogle Tasks・追跡Calendar枠の連動削除
- 通知元、状態、期限、配置、再計画回数、全Calendar枠を確認できるタスク詳細
- 複数選択したタスクの確認付き一括完了・一括削除
- 稼働時間、余白、1日の上限、土日、昼休みの設定
- 月〜日それぞれの稼働ON／OFFと開始・終了時刻
- 分単位で変更できる休憩開始・終了時刻
- 未完了タスクの自動再配置、再計画上限、要確認状態
- 要確認タスクの再開とGoogle Tasksへの完了同期
- 明示同意と暗号化APIキーによる任意のAIスケジューリング
- タイトル・本文・送信者・通知元を除外した匿名メタデータMapper
- AIによる同期前の優先順位・期限リスク評価と、障害時の端末内スケジューラへの自動フォールバック
- Calendar Free/Busyから生成した匿名の日別空き分数による過密日判断
- AIが提案する25／30／45／60分単位での長時間タスク分割

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

1. Hishoを起動し、「設定」から通知へのアクセスを許可します。
2. 設定画面で取得対象のアプリを選択します。
3. 設定画面からGoogleアカウントを接続し、TasksとCalendarへのアクセスを許可します。
4. 通知受信後、「端末内キュー」で同期待ち件数を確認します。
5. 必要なら「タスク推定を確認・修正」で編集します。
6. 「今すぐ同期」を押すか、バックグラウンド同期を待ちます。

AI支援は初期状態でOFFです。「AI支援を設定」で送信項目を確認し、OpenAI APIキーを保存して明示的に同意した場合のみ有効になります。APIキーはAndroid Keystoreの鍵で暗号化されます。

### 推奨する実機検証

1. 対応アプリから通知を発生させ、同一通知の更新が重複しないことを確認します。
2. 「タスクを手動で追加」からタイトル、期限、工数、優先度を指定し、TasksとCalendarへ同期されることを確認します。
3. 未同期タスクのタイトル、期限、優先度、工数を編集して同期します。
4. Calendar枠が稼働時間内にあり、既存予定・土日・昼休みを避けることを確認します。
5. Sは25分、Mは60分、Lは2枠、XLは4枠になることを確認します。
6. 同期済みタスクを編集し、以前の枠が整理されて再配置されることを確認します。
7. 自動再配置を有効にし、未完了タスクが予定終了後に移動することを確認します。
8. 再計画上限で「要確認」になり、再開または完了にできることを確認します。
9. AI支援を有効にし、複数の同期待ちタスクでAI適用・過密状態が表示されること、長時間タスクが提案された長さに分割されることを確認します。
10. AI通信を失敗させても、通常の優先順と最大60分の分割で同期が継続することを確認します。

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
- 0.16.0より前に作成された未追跡予定は、Calendar側の移動・削除を自動検出できません。
- 通知形式の違いにより、タイトル・期限・工数の推定が誤る場合があります。
- 祝日と希望時間帯は未対応です。
- アカウント切断、OAuth権限取り消し、全データ削除UIは未実装です。
- タイトルや本文をAIへ送る意味的な要約・推定・タスク分解は、プライバシー設計確定まで未実装です。

### 今後の計画

1. 祝日と希望時間帯
2. Android共有メニューと音声入力
3. アカウント切断、OAuth権限取り消し、データ削除
4. DB移行、API、オフライン、大量通知、バッテリー試験
5. 本番署名、プライバシーポリシー、Google Play公開対応
6. 同意を前提にした本文AI処理（現在は匿名スケジューリング・過密判断・分割提案まで実装済み）

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
- Manual task creation with a title, deadline, effort, and priority inside Hisho
- Manual title, deadline, priority, and effort editing
- Synced-task updates and Calendar rescheduling
- Two 60-minute blocks for L and four blocks for XL tasks
- Persistent event ID, order, time, and generation tracking for every split Calendar block
- Calendar-side time changes are imported for tracked blocks; deleted blocks stop in Needs attention
- A calm home screen focused on the next task, automation health, and actionable problems
- Direct task-review paths for deadline risk, sync failures, attention items, and pending work
- Direct Google Calendar access with operational controls consolidated in Settings
- Task-state filters and title/source search
- Confirmed task deletion synchronized with Google Tasks and all tracked Calendar blocks
- Task details covering source, state, deadline, schedule, recovery count, and every Calendar block
- Confirmed bulk completion and deletion for selected tasks
- Working hours, buffers, daily capacity, weekend, and lunch settings
- Per-day enablement and start/end times from Monday through Sunday
- Custom break start and end times with minute precision
- Optional unfinished-task recovery with configurable limits
- Restart and Google Tasks completion actions for items needing attention
- Optional AI scheduling with explicit consent and an encrypted API key
- An anonymous metadata mapper that excludes titles, bodies, senders, and notification sources
- AI ordering and deadline-risk prioritization before sync, with automatic deterministic fallback
- Overloaded-day detection using anonymous daily free-minute totals derived from Calendar Free/Busy
- AI-selected 25/30/45/60-minute maximum blocks for long tasks

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

AI assistance is off by default. It is enabled only after reviewing the transmitted fields, saving an OpenAI API key, and explicitly consenting in **AI assistance settings**. The API key is encrypted with an Android Keystore-backed key.

### Recommended device validation

1. Generate supported-app notifications and verify duplicate updates are ignored.
2. Create a task with a title, deadline, effort, and priority using **Add task manually**, then verify Tasks and Calendar synchronization.
3. Edit a pending task's title, deadline, priority, and effort, then sync it.
4. Verify Calendar blocks stay within working hours and avoid events, weekends, and lunch.
5. Verify S uses 25 minutes, M uses 60 minutes, L creates two blocks, and XL creates four.
6. Edit a synced task and verify its prior blocks are replaced by a new schedule.
7. Enable recovery and verify an unfinished task moves after its scheduled end.
8. Verify a task exceeding its limit becomes **Needs attention** and can be restarted or completed.
9. Enable AI assistance and verify its applied/overloaded status and suggested block lengths with multiple pending tasks.
10. Force an AI request failure and confirm synchronization continues with deterministic ordering and 60-minute blocks.

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
- Events created before 0.16.0 are not tracked and cannot be reconciled automatically.
- Notification-format differences can cause incorrect title, deadline, or effort inference.
- Public holidays and preferred time windows are unsupported.
- Account disconnect, OAuth revocation, and full data-deletion controls are not implemented.
- Content-based AI summarization, estimation, and decomposition remain disabled until their privacy design is finalized.

### Roadmap

1. Add public holidays and preferred time windows.
2. Add Android sharing and voice capture.
3. Add account disconnect, OAuth revocation, and data-deletion controls.
4. Expand migration, API, offline, load, and battery testing.
5. Add production signing, privacy documentation, and Google Play release readiness.
6. Optionally add consent-based content processing (anonymous scheduling, load detection, and block suggestions are implemented).
