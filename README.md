# Hisho / 秘書

**Version: 0.31.0 (33)** — Personal use only / 個人利用専用

## 日本語

通知をそのまま予定に変換する方式を停止し、必要性を判定してGoogle Tasksへ登録する方式に変更しました。一般公開、ストア公開準備、公開用ポリシー作成は対象外です。認証の維持、データ保護、個人端末の更新時のデータ保持は引き続き対象です。

### 登録の順序

1. **除外**：指定ワード・通知元アプリに一致した通知は登録せず、AIにも送信しません。
2. **最優先登録**：除外されず、最優先ルールに一致した通知をAIなしで登録します。優先度HIGHと「【最優先】」のタイトルで識別します（Google Tasks APIには優先度フィールドがありません）。
3. **AI判定**：未一致の通知のタイトル・本文・通知元をOpenAI Responses APIへ送信し、必要／不要／確認待ちを判定します。
4. **確認待ち**：AI未設定・通信失敗・拒否・不完全な応答・曖昧な通知は自動登録しません。確認画面で元の通知を読み、名前を修正して承認・除外・再判定できます。

除外と最優先が競合する場合は除外が優先です。ワードは部分一致で、英字大小・全角半角を正規化します。通知元はGmail、Slack、Discord、LINEを画面から選択します。取得OFFのアプリは処理対象外です。ルールは未同期通知の処理にも適用しますが、登録済みタスクを自動削除しません。手動追加は本人の明示入力としてAI判定を省略します。

AIには通知を命令でなく未信頼のデータとして扱わせ、構造化された応答のみ受け入れます。ただし誤判定を完全には防げず、実際の通知での精度評価が必要です。

### Google TasksとCalendar

- 新規Calendar予定・作業ブロックの作成、旧時間枠通知、自動再配置は停止しました。
- Google Tasksの作成・編集・完了・削除を同期します。Google側の完了・削除も次回同期で反映します。
- Google Tasks APIは期限の日付だけを保存し、時刻部分は破棄します。日本時間の日付がずれない形で送信します。時刻指定のタスク配置は実装していません。[Google公式仕様](https://developers.google.com/workspace/tasks/reference/rest/v1/tasks)
- 以前作成したCalendar予定は自動削除しません。「設定 → 整理する予定を選ぶ」で対象を選択し、再確認後に予定だけを削除できます。Google Tasksは残ります。
- 端末がIDを追跡していない旧予定は整理対象にできません。Calendar上で個別に確認してください。
- 個々のタスクを削除する操作は、そのタスクと追跡している旧Calendar予定の両方を削除します。

### 設定手順

1. 「設定 → 除外・最優先ルールを設定」でルールを保存。
2. 「AIスマートフィルターを設定」で送信内容を確認し、本文送信を有効にしてAPIキーを保存。以前の匿名AI同意は本文送信の同意として流用しません。
3. 通知へのアクセスと取得対象アプリを設定。
4. Googleアカウントを接続。
5. 確認待ちは「タスクを確認する → 詳細・操作」から承認・除外・再判定。

API利用料が発生します。タイトル・本文内の人名・会社名・URL等も送信対象です。除外ルールを先に設定してください。APIキーと保存通知本文はAndroid Keystoreで暗号化します。Google同期成功後は元の通知本文を消去します。APIリクエストはstore=falseですが、送信先のデータ保持ポリシーは適用されます。本文・キーをログ出力しません。

### その他の機能

- 通知更新の重複排除、SQLiteの永続キュー、WorkManagerの定期同期と再試行
- タイトルだけの手動追加、Enter保存、任意項目の展開
- Androidのテキスト共有・確認付き音声入力（端末の認識サービスへの外部送信があり得ます）
- タイトル・期限・工数・優先度の編集、検索・状態フィルター・一括操作
- 判定理由と除外履歴の表示

### 開発環境

Android 8.0以上、Google Play services、Java 17、Android SDKが必要です。
Google CloudではTasks APIを有効化します。旧Calendar予定の整理にはCalendar APIも必要です。
既存OAuth設定はtasks、calendar.events、calendar.events.freebusyの権限を要求します。Calendar新規作成とFree/Busy取得は現在行いません。
Android OAuthクライアントはパッケージ名 app.hisho と端末に配布するAPKの署名証明書に対応させます。クライアントIDは app/build.gradle.kts に設定。APIキーや署名鍵をGitへコミットしないでください。

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 残る検証と改善

- 実通知でのAI精度、失敗時に確認待ちになること、誤登録率の測定
- Google側の完了反映、旧予定の選択式整理、オフライン復帰の実機検証
- アカウント切断・OAuth権限取り消し・全データ削除UI
- 大量通知、長期利用、DB移行、バッテリー検証

旧スケジューラ関連の一部コードとテストは履歴互換のため残っていますが、新規配置には使いません。[検証項目](docs/smart-filter-validation.md)

## English

Hisho is a personal-use Android notification-to-task app. It now filters notifications before creating Google Tasks instead of creating Calendar time-block events. Public distribution and store-release preparation are out of scope; authentication, data protection, and safe personal updates remain in scope.

### Decision order

1. Exclusion rules (keyword or source app): no task and no AI request.
2. Force rules: register without AI, with local HIGH priority and a visible priority title prefix. Exclusion always wins.
3. Otherwise send notification title, body, and source to OpenAI to classify as task, ignore, or review.
4. Missing configuration, network errors, refusals, malformed/incomplete output, and uncertainty are held for review, never automatically registered.

Keywords use normalized case/width-insensitive substring matching. Source apps are selectable by name. Rules also apply to pending notifications, not retroactively to already-synced tasks. Manual input bypasses classification. Review items can be inspected, renamed, approved, ignored, or reclassified.

The classifier treats notification content as untrusted data and accepts structured output only. This does not guarantee perfect accuracy or immunity to prompt injection; real notification evaluation remains necessary.

### Tasks-only behavior

- No new Calendar events, execution-block reminders, or automatic block recovery.
- Create, edit, complete, and delete Google Tasks; observe completion/deletion from Google on subsequent syncs.
- The Tasks API stores dates, not due-time components. Local dates are preserved when formatting requests; timed task placement is not implemented. [Google reference](https://developers.google.com/workspace/tasks/reference/rest/v1/tasks)
- Legacy Calendar cleanup is opt-in: choose tracked items and confirm. It deletes events but retains Tasks. Untracked events require manual inspection.
- Deleting a task through the normal deletion action also deletes its tracked legacy events.

### Setup and privacy

Configure exclusion/force rules first, then enable notification-content AI consent and save an API key, grant notification access, and connect Google. Previous anonymous-metadata consent does not authorize the new content filter.

Names, companies, and URLs in notification content may be transmitted. API usage is billed. Stored notification payloads and API keys are encrypted with Android Keystore; raw payloads are cleared after successful Google sync. Requests use store=false; provider retention policies still apply. Sensitive payloads and keys are not logged.

The API uses the existing gpt-5.4-mini model and Responses structured outputs. [OpenAI documentation](https://developers.openai.com/api/docs/guides/structured-outputs)

### Build and remaining work

Use Java 17 and Android SDK; run the Gradle/test and adb commands above. Requires Android 8+, Google Play services, Tasks API, and an Android OAuth client matching the package/signing certificate. Calendar API is needed only for legacy cleanup. Existing OAuth scopes still include Tasks, Calendar events, and Free/Busy, but no new events or Free/Busy reads occur.

Remaining work includes AI quality evaluation, end-to-end completion/cleanup and offline testing, disconnect/revocation/data-deletion controls, and long-term load/battery testing. Legacy scheduler code/tests remain but are not used for new placement. See the [validation checklist](docs/smart-filter-validation.md).
