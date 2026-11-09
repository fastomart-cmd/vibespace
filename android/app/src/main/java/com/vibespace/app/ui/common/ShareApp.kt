package com.vibespace.app.ui.common

import android.content.Context
import android.content.Intent
import com.vibespace.app.R

const val APP_DOWNLOAD_URL = "https://github.com/fastomart-cmd/vibespace/releases/latest/download/VibeSpace.apk"

/** Opens the Android share sheet (WhatsApp, Telegram, SMS…) with an invite and the APK download link. */
fun shareApp(context: Context) {
    val text = context.getString(R.string.share_app_message, APP_DOWNLOAD_URL)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.app_name))
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(send, context.getString(R.string.share_app)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
