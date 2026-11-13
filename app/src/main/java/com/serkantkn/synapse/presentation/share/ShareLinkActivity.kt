package com.serkantkn.synapse.presentation.share

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.serkantkn.synapse.data.service.SynapseService

/**
 * Invisible share target: "Share → Synapse" on a link opens it in the Mac's browser.
 * Hands the URL to SynapseService and closes at once.
 */
class ShareLinkActivity : Activity() {

    companion object {
        private val URL_REGEX = Regex("""https?://[^\s<>"]+""", RegexOption.IGNORE_CASE)

        fun extractUrl(text: String?): String? =
            text?.let { URL_REGEX.find(it)?.value?.trimEnd('.', ',', ')', ']', '!', '?') }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = if (intent?.action == Intent.ACTION_SEND) {
            extractUrl(intent.getStringExtra(Intent.EXTRA_TEXT))
        } else null

        if (url == null) {
            Toast.makeText(this, "Paylaşılan içerikte link bulunamadı", Toast.LENGTH_SHORT).show()
        } else {
            startService(
                Intent(this, SynapseService::class.java)
                    .setAction(SynapseService.ACTION_SHARE_LINK)
                    .putExtra(SynapseService.EXTRA_URL, url)
            )
        }
        finish()
    }
}
