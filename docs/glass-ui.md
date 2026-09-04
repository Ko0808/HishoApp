# Glass-inspired UI — 0.32.0

## Scope

Presentation-only refresh; preserve page destinations, notification rules, AI requests, Google synchronization, stored data, and confirmations.

- Shared GlassActivity / GlassUi across home, settings, review, manual capture, AI, rules, and legacy weekly settings.
- Light blue/mint/lilac backdrop, near-white translucent surfaces with highlights and rounded corners.
- Blue primary actions, restrained secondary actions, red destructive actions, distinct disabled controls.
- Native ripple and short press/release scale animation; follows the system animation duration scale.
- Focus outlines, 54dp minimum button height, flexible horizontal actions, system font scaling.
- Visible back action, system-bar/cutout/keyboard insets, initial focus preventing unwanted entry scroll.
- Rounded native confirmation dialogs; no changes to confirmation semantics.

No live background capture, blur loop, image assets, or additional network calls. This is an Android interpretation, not an iOS Liquid Glass implementation.

## Verification

Run `gradlew.bat testDebugUnitTest assembleDebug lintDebug`.
Pixel 7a visual checks completed: home, empty manual capture, keyboard and voice-consent dialog layout. No real task was saved/deleted and no AI consent was changed for visual testing. The dialog surface was made opaque after inspection to prevent underlying text showing through. Settings/review on additional font sizes remain follow-up checks.
Existing automated tests exercise business logic; they do not prove animation smoothness, accessibility, or layout at all screen/font sizes. Additional devices, TalkBack, and large-font testing remain useful follow-up checks.
