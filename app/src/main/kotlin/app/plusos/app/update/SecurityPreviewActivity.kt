// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 The +PLUS for GrapheneOS project
package app.plusos.app.update

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * B4: GrapheneOS's setup asks the update app (`app.seamlessupdate.client.SECURITY_PREVIEW_SETTINGS`, +PLUS here) whether
 * to follow security preview releases, and doesn't move on until it answers. Like GrapheneOS's own Updater, the choice
 * is ticked by default here and switches to the "<channel>-security-preview" release channels. Also reachable from the
 * +PLUS screen. Protected by OPEN_SECURITY_PREVIEW_SETTINGS (internal|setup), as in the stock Updater.
 */
class SecurityPreviewActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val channel = ReleaseChannel(this)
        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = CheckBox(this).apply {
            text = "Receive security preview releases"
            isChecked = savedInstanceState?.getBoolean(KEY) ?: (channel.securityPreviewChoice ?: true)
        }
        setContentView(ScrollView(this).apply {
            addView(LinearLayout(this@SecurityPreviewActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, pad * 2, pad, pad)
                addView(TextView(this@SecurityPreviewActivity).apply {
                    text = "Security preview releases"; textSize = 24f; setTypeface(typeface, Typeface.BOLD)
                })
                addView(TextView(this@SecurityPreviewActivity).apply {
                    text = "GrapheneOS offers security preview releases: they include Android security patches before " +
                        "they are publicly disclosed. The only difference from regular releases is those early patches; " +
                        "their source code can be published only after the official disclosure date. GrapheneOS " +
                        "recommends turning this on.\n\n+PLUS follows the releases you choose here and signs them with " +
                        "your key, as always."
                    setPadding(0, pad / 2, 0, pad / 2)
                })
                addView(box)
                addView(Button(this@SecurityPreviewActivity).apply {
                    text = if (intent.hasExtra(SETUP_EXTRA) || callingActivity != null) "Next" else "Save"
                    setOnClickListener {
                        channel.securityPreview = box.isChecked
                        setResult(RESULT_OK)
                        finish()
                    }
                })
            })
        })
        tag = box
    }

    private var tag: CheckBox? = null

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        tag?.let { outState.putBoolean(KEY, it.isChecked) }
    }

    private companion object {
        const val KEY = "security_preview"
        const val SETUP_EXTRA = "firstRun"
    }
}
