# MAXLEVEL DETOX — Reference UI Redesign

This version is a **code-level Flutter UI redesign** based on the supplied MAXLEVEL DETOX reference UI boards.

## Scope
- UI/visual layer only.
- Existing Kotlin enforcement, NativeBridge, AppState, routes, Worker/API, D1, persistence and feature logic are intentionally preserved.
- No mock/dummy runtime values were introduced.
- Screens continue to render their existing real/native/cloud state.
- If real data is unavailable, the existing empty/loading/error state is shown instead of fabricated values.

## Visual direction
- Deep navy/blue premium background with subtle grid atmosphere.
- Cyan/blue primary actions and highlights.
- Violet/purple premium surfaces.
- Mode-specific accents: Study teal, Detox fuchsia, Monk amber, Lock red, Safety cyan, Prime violet.
- Rounded floating cards, thin blue borders, soft glow, layered gradients.
- Modern Material controls, dialogs, tabs, inputs, switches, progress indicators and navigation.
- Vector-painted MAXLEVEL brand mark for the code UI; no generated screenshot/mockup is embedded as a screen.

## Important
The supplied reference boards are treated as the visual target. Exact source artwork/font files from the mockups were not assumed; the visual language is recreated using Flutter widgets, gradients, typography and vector drawing.

Flutter/Dart compilation was not available in the build environment used for this delivery, so this package has not been represented as APK-build-verified.
