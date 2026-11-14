package com.brbrs.zaman.data.caldav

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.brbrs.zaman.MainActivity

/**
 * Catches the `zaman://login-callback` redirect Nextcloud's Login Flow v2
 * browser page can optionally use, and hands control back to MainActivity.
 * The actual polling happens in LoginFlowV2Auth regardless of whether this
 * redirect ever fires — it's a convenience, not a requirement.
 */
class LoginFlowRedirectActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
