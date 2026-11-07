# Autoplay mode

**Assist mode is the default and never acts on the game.** Autoplay is a separate, opt-in mode. This reverses the project's
original "suggestion-only" boundary at the owner's explicit request; Assist behaviour is unchanged. Automating a game may be
against its terms of service; use it at your own risk.

## How input works (it is NOT part of MediaProjection)
MediaProjection only captures the screen. Gestures need Android's accessibility permission: the optional
`AutoplayAccessibilityService` (`canPerformGestures`, no window content access, event type window-state-changed only, used to
know which app is in front). The user must enable it in Android Settings > Accessibility. On Android 13+ a sideloaded APK
is blocked from this until the user opens App info > the three-dot menu > "Allow restricted settings". Turning the service
off disables Autoplay immediately.

## Flow (all in `AssistantService`)
1. Mode button -> Autoplay; Start (the game must be in front; its package is remembered).
2. Recognition must be GOOD (verified by two identical clean scans) and a solver result must exist.
3. Best move -> `AutoplayPlanner`: finger starts on a filled cell of the tray piece (`PieceGrab`) and ends on the matching
   board cell, plus a learned drop offset. The drag is dispatched as one stroke (550 ms).
4. Wait for the game's animation (1.3 s), rescan with the overlay hidden, recognise again.
5. Verify: the new board must equal the rules engine's predicted board (line clears included).
   - match: counted as verified, continue.
   - mismatch but explained by a unique alternative placement: the landing offset is learned and applied, continue from the
     real board (stops after 3 in a row).
   - board unchanged: drag not accepted, retry (stops after 3).
   - anything else: stop and ask for a check.
6. Recalculate and repeat.

## Safety rules
- Never moves without a verified recognition; never moves while the screen is changing; never moves if the panel is in the drag path.
- Stops (status NEEDS ATTENTION, sticky message) on: recognition doubtful for > 12 s, accessibility service off, game not in
  front, illegal/unexplained board, no legal move, screen size change, internal error.
- Temporary recognition delays (animations) are tolerated for 12 s while it waits and does NOT move.
- E-STOP (panel button and notification action) ends Autoplay at once and returns to Assist.

## NOT validated
No gesture has ever been sent to a real game by this code. Unknown until tested on a device: whether the game accepts
accessibility drags, how the dragged piece is offset from the finger (the learned offset starts at 0, so the first one or
two moves may land off target), and whether `dispatchGesture` timing suits the game.
