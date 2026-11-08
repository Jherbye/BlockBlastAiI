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
1. Mode button -> Autoplay; Start. (There is NO foreground-app check: it proved unreliable, a Vivo system overlay was
   mistaken for the game and caused spurious stops. "The game is on screen" is established by recognition itself.)
2. Recognition must be GOOD (verified by two identical clean scans) and a solver result must exist.
3. Best move -> `AutoplayPlanner` gives the nominal finger path: from a filled cell of the tray piece (`PieceGrab`) to the
   matching board cell, plus the learned drop offset.
4. **Closed-loop drop** (`closedLoopDrag`): press and hold the piece; move near the target WITHOUT lifting; capture the
   screen; `DraggedPieceLocator` finds the dragged piece (changed pixels vs the pre-drag frame, size and cell shape must
   match, the vanished tray piece and anything far from the finger are ignored); nudge the finger by the measured error;
   repeat up to 4 times; release only when the piece is within 0.2 cell of the target centre. If the piece cannot be
   found or aligned, the finger returns to the tray and lifts (drop cancelled, nothing placed). The measured correction
   becomes the stored drop offset, so later moves start almost aligned. (Reason: games often draw the dragged piece well
   above the finger; dropping the finger on the target then puts the piece somewhere else.)
5. Wait for the game's animation (1.3 s), rescan with the overlay hidden, recognise again.
6. Verify: the new board must equal the rules engine's predicted board (line clears included).
   - match: counted as verified, continue.
   - differs: look once more (animations / special tiles may still be settling) before judging.
   - then: explained by a unique alternative placement -> learn the offset, continue from the real board (stops after 3
     in a row); board unchanged -> drag not accepted (stops after 3); otherwise stop and say how many cells differ.
7. Recalculate and repeat.
Every stop and every hover measurement is written to the Events list in the app's Diagnostics.

## Safety rules
- Never moves without a verified recognition; never moves while the screen is changing; never moves if the panel is in the drag path.
- Stops (status NEEDS ATTENTION, sticky message) on: recognition doubtful for > 12 s, accessibility service off, game not in
  front, illegal/unexplained board, no legal move, screen size change, internal error.
- Temporary recognition delays (animations) are tolerated for 12 s while it waits and does NOT move.
- E-STOP (panel button and notification action) ends Autoplay at once and returns to Assist.

## Reported on a real phone (first test)
The previous open-loop version stopped by itself (foreground-app misdetection) and dropped pieces where nothing was placed
(dragged piece drawn above the finger; the offset was never measured). Both are addressed above; the new closed loop has NOT
been tested on the phone yet.

## NOT validated
The drag finder is validated on real pixels only by pasting a tray piece onto a real screenshot (Python mirror; found within
~3 px when hovering over empty cells; fails safely when hovering over same-coloured blocks). No gesture sequence of the new
loop has run on a real game. Unknown until tested: whether the game keeps drawing the dragged piece while the touch is held
by an accessibility stroke, whether it needs a different press time, and special tiles (countdown blocks) that change the
board in ways the rules engine cannot predict.
