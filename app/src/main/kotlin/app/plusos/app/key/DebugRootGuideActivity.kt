// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 The +PLUS for GrapheneOS project
package app.plusos.app.key

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Typeface
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * R2/R3 (first phone test, 2026-10-31): the one-time Magisk setup, explained by +PLUS rather than left to Magisk's own
 * stub dialog. Opens by itself once after the first boot with debug root, and from the debug root notification and the
 * +PLUS screen.
 */
class DebugRootGuideActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DebugRootState(this).guideShown()
        val pad = (20 * resources.displayMetrics.density).toInt()
        fun text(s: String, size: Float = 16f, bold: Boolean = false) = TextView(this).apply {
            text = s; textSize = size; if (bold) setTypeface(typeface, Typeface.BOLD); setPadding(0, pad / 3, 0, pad / 3)
        }
        setContentView(ScrollView(this).apply {
            addView(LinearLayout(this@DebugRootGuideActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, pad, pad, pad)
                addView(text("Debug root: one-time setup", 24f, bold = true))
                addView(text("This phone now runs with Magisk in its boot image, signed with your key. Magisk needs its app " +
                    "once to approve root for the adb shell. With the phone connected over USB:"))
                addView(text("1. On the computer run the command below. On the phone Magisk asks to finish its setup: " +
                    "tap OK. It installs the Magisk app.\n\n" +
                    "2. When Magisk asks whether Shell may have superuser access, grant it (Forever).\n\n" +
                    "3. In the Magisk app open Settings > Superuser access and choose ADB only, so no app can ever get root.\n\n" +
                    "4. If Magisk asks for additional setup, tap Cancel."))
                addView(text("Never use Magisk's Install button or its Uninstall Magisk button on this phone. They rewrite the " +
                    "boot partition, which then fails verified boot with your locked bootloader. +PLUS turns Magisk on and " +
                    "off: use End debug root.", bold = true))
                addView(text("Root for the adb shell:", bold = true))
                addView(text(COMMAND, 14f).apply { typeface = Typeface.MONOSPACE; setTextIsSelectable(true) })
                addView(Button(this@DebugRootGuideActivity).apply {
                    text = "Copy command"
                    setOnClickListener {
                        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("su", COMMAND))
                        text = "Copied"
                    }
                })
                addView(text("Leave wireless debugging off to keep root to USB. The Magisk settings stay on the phone, so the " +
                    "next time you turn debug root on, these steps are already done."))
                addView(Button(this@DebugRootGuideActivity).apply { text = "Done"; setOnClickListener { finish() } })
            })
        })
    }

    companion object {
        const val COMMAND = "adb shell /debug_ramdisk/su -c id"
    }
}
