// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 The +PLUS for GrapheneOS project
package app.plusos.app

import android.content.Context
import android.content.pm.ApplicationInfo

/** Dev-only paths (overrides for our own source builds) are available only in a debuggable +PLUS build. */
object BuildConfigCompat {
    fun isDebuggable(ctx: Context): Boolean = (ctx.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
}
