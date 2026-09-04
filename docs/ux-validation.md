# UI/UX改善の検証 / UX validation — 0.30.0

残りの改善Phase 2〜5の実装を統合。コード実装と実機の動作保証は分けて扱います。
Remaining UX phases 2–5 are implemented together; implementation is not a claim of complete device validation.

## 今回の結果 / Results — 2026-09-04

- 自動テスト42件成功。APK作成成功。Android Lintはエラーなし（警告は残存）。
- Pixel 7aへデータ保持で更新インストール成功。
- 実機でテキスト共有による下書き表示、任意項目の折りたたみ／展開、音声入力前の説明、既存の通知アクセス・Google接続を認識して初期設定ステップ3へ進むことを確認。
- 実タスクの完了・削除や音声認識の外部送信は検証目的では実行していません。
- 通知到着・通知操作によるGoogle更新・画面回転・音声認識・省電力での継続検証は未実施です。

42 unit tests passed; APK built; lint has no errors (warnings remain). Updated Pixel 7a without clearing data and checked shared drafts, collapsed/expanded fields, and voice consent. Live reminder delivery/actions, rotation, speech recognition, and power-saving validation remain pending.

## データを変更しない確認 / Non-mutating checks

- ホーム → セットアップを続ける。権限・接続の状態に応じたステップが表示される。
- 設定 → Google再接続、通知設定、AIの送信範囲、自動再配置の説明がある。
- 手動追加はタイトルと保存が最初に見え、期限・工数・優先度は展開すると見える。
- テキスト共有先にHishoが現れ、共有文が下書きになる。戻るだけでは保存されない。
- 入力中の画面回転でタイトル・期限・工数・優先度・展開状態が維持される。
- 音声入力前に外部処理の可能性が表示され、キャンセルしても既存下書きは変わらない。
- 旧予定の詳細は配置理由を推測せず「記録がありません」と表示する。

## テスト用タスクで確認 / Use a disposable test task

実行にはGoogle Tasks/Calendarの書き込みが伴います。実際のタスクをテスト用に変更しないでください。
These checks write to Google Tasks/Calendar. Do not repurpose real tasks as test fixtures.

1. タイトルだけで保存、Enter保存、連打で重複しないことを確認。
2. Google同期後、新規枠の詳細に配置ルール・余白・日上限・期限との関係が表示される。
3. Calendarで枠を移動して同期すると、元の配置理由を変更後の理由として表示しない。
4. 開始5分前と開始時の通知を確認。通知を拒否／チャンネル無効にするとホームに案内が出る。
5. 「15分後」→アプリ再起動・同期を挟んでも、元の時刻の通知が復活しない（終了前の枠で試す）。
6. 通知の「完了」でタスク全体が完了し、「再配置」で再配置待ちになる。古い枠は通知しない。
7. 長いタスクの次の分割枠がホームに表示される。
8. 機内モード→復帰、端末再起動、省電力・画面OFFで通知と再試行を確認する。
9. 音声認識を開始し、結果が下書きに追加されるだけで自動保存されないことを確認する。

## 自動テスト / Automated checks

`gradlew.bat testDebugUnitTest assembleDebug lintDebug`

通知の期限切れ・遅延・重複・スヌーズ・予定変更時の識別と、配置説明の期限判定・設定値を回帰テストで確認します。
Regression tests cover reminder expiry, lateness, duplicates, snooze, schedule identity, and deadline/settings explanations.
