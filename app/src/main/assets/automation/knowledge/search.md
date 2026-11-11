# Search Flow — shared usage notes for the planner

- Search icon: usually located in the top bar as an icon-only magnifier with no text label; use vision coordinates when the node tree search fails.
- Screen after tapping search: search input field appears at the top, and the soft keyboard opens.
- Typing in search: typing needs the field focused first (tap field, wait for keyboard to appear, set text via ACTION_SET_TEXT, read back to verify, then press Enter).
- Scrolling: feed scrolls are normal and expected; repeated scrolls are not a loop failure.
