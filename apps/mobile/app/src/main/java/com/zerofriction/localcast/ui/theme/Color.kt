package com.zerofriction.localcast.ui.theme

import androidx.compose.ui.graphics.Color

// Broadcast-console palette (designs/mobile-app.html — the design source of
// truth; these tokens are its port contract). Deliberately dark-first: the
// product's receiver is dark; the phone matches. The accent is broadcast
// amber (tally/REC lamps); it replaced #A8C7FA, which was stock Material 3
// dark primary — an unmodified default theme. Derived shades (the prototype
// derives with color-mix; the fixed results land here):
//   onSurfaceVariant = on 62% over the background, outline = on 13% over the
//   surface.
val CastPrimary = Color(0xFFF2A93B)
val CastOnPrimary = Color(0xFF1A1206)
val CastBackground = Color(0xFF101418)
val CastSurface = Color(0xFF161B22)
val CastOnBackground = Color(0xFFE6EAF2)
val CastOnSurface = Color(0xFFE6EAF2)
val CastOnSurfaceVariant = Color(0xFF95999F)
val CastOutline = Color(0xFF31363D)
val CastError = Color(0xFFF2B8B5)
