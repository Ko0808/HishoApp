# HIGに基づくUI/UXレビューと再設計 — v0.33.0

## 目的と範囲

「通知から必要な行動だけを取り出す」という機能を保ち、確認・登録・完了までの判断負荷を下げる。AndroidネイティブViewのまま実装し、AppleのAPI、SF Symbols、フォント、CSSの移植は行わない。

指定の[日本語HIG](https://developer.apple.com/jp/design/human-interface-guidelines)はこの環境で本文を表示できなかったため、Apple公式の英語版配信データから関連章を読んだ。HIG全章を網羅した／Apple認定に適合したという意味ではない。以下はモバイルに関係する原則をAndroidに翻訳した設計判断。

## レビューと対応

| 課題 | 設計判断・実装 | 根拠 |
| --- | --- | --- |
| 大きな同形ボタンが並び、何をすべきか不明 | ホームの状況カードで設定・要確認・タスク確認のうち一つだけを主操作に。主要ボタンの役割を明示指定 | [Buttons](https://developer.apple.com/design/human-interface-guidelines/buttons) |
| 本文にもガラス表現を使い、操作と内容の差が弱い | 本文カードは不透明な白。淡い素材表現をナビゲーション領域に限定 | [Materials](https://developer.apple.com/design/human-interface-guidelines/materials) |
| 一覧の操作が内容を下へ押し出す | 状態切り替えを直接表示、検索・その他・複数選択をまとめる。一括操作は選択中だけ表示 | [Layout](https://developer.apple.com/design/human-interface-guidelines/layout) |
| 判断材料と承認が別メニュー | 確認画面に元通知・判定理由・登録・再判定・除外を集約。高度な編集と旧情報は従来メニューへ | [Layout](https://developer.apple.com/design/human-interface-guidelines/layout) |
| 同期表示が大きすぎる、または消えてわからない | 各画面に同じ同期領域。状態と時刻は常時、詳細は展開。処理中・エラー時は説明を自動表示 | [Progress indicators](https://developer.apple.com/design/human-interface-guidelines/progress-indicators) |
| 装飾で操作が遅くなるリスク | 押下100ms・縮小1.5%、確認画面140msフェードのみ。連続装飾、パララックス、ライブぼかし、紙吹雪は使わない | [Motion](https://developer.apple.com/design/human-interface-guidelines/motion) |
| 小さい文字・色頼み・入力ラベル不足 | 16sp本文・可変高、状態は文字でも表現。主要色4.5:1以上をテスト。入力ラベル・アクセシビリティ名を追加 | [Typography](https://developer.apple.com/design/human-interface-guidelines/typography)、[Accessibility](https://developer.apple.com/design/human-interface-guidelines/accessibility) |

## 想定する操作

1. ホームを開く → 確認件数と次の行動を見る。
2. 「N件を確認する」→ 要確認一覧 →「内容を確認する」。
3. 元通知・判定理由を読んで登録、再判定、除外。編集は「名前・期限などを編集 / 詳細・操作」。
4. 同期領域の「同期」で実行。進行状況と結果を同じ場所で確認する。
5. 「同期済み」→ タスクを開く →「完了にする」。Google Tasksでの管理も維持。

未同期・除外履歴・完了・期限注意・すべての絞り込み、検索、複数選択、編集、共有、音声、通知元設定、API同意、接続診断、旧Calendar整理は維持する。確認待ちを自動承認する変更、AIのモデル・プロンプト・課金・データ送信の変更、DB移行はない。

## アクセシビリティと動き

- Androidでは48dp以上の操作領域を採用（Appleのpt値をそのまま移植しない）。
- 文字拡大時はボタンだけの横並びを縦積みに変更。状態フィルターは横スクロール可能。
- 「設定 → 表示と操作 → 動きを減らす」とシステムのアニメーション無効化を尊重。動きがなくてもテキストで状態がわかる。
- 同期の割合・残り秒数は推測表示しない。既存の実処理件数と工程だけを使う。
- 削除確認、音声送信の確認、API本文送信への同意は省略しない。
- 現状はライトテーマ。完全なダークテーマ、全機種・横画面・TalkBackによる通し操作は別途検証が必要。

## 検証

- 単体テスト59件成功（うち主要配色のコントラスト検証2件）、APKビルド成功、lintエラーなし。既存の警告は残る。
- Pixel 7aでホーム→要確認一覧の遷移、主要操作の強調、カードの可読性、同期欄、状態フィルターを視覚確認。操作群が大きすぎた初稿を修正し、1画面内に最初のタスク操作が入ることを確認した。
- 最後に短い同期状態ラベルと通知本文の段階表示を調整し再ビルド。調整後の全画面を実機確認したわけではない。
- 文字200%の検証中に別アプリが前面になったため、端末操作を中止。フォント設定は元の1.0へ復元した。文字200%・確認ダイアログ・キーボード・動きを減らす設定の通し操作は未検証として残す。
- 実際のタスクの承認・除外・完了・削除や有料AIテストは、今回のUI検証では意図的に実行していない。

## English summary

The redesign applies relevant Apple HIG principles to native Android, not Apple's rendering APIs. Home highlights a single contextual next action; task review groups evidence and decisions; opaque content surfaces contrast with restrained navigation material. Sync status remains visible with optional detail. Existing filtering, approval, editing, completion, sharing, consent and legacy cleanup capabilities remain available. Motion is short, optional and nonblocking. This is not a claim of complete HIG compliance or a full accessibility audit.
