package com.maxleveldetox

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.maxleveldetox.overlay.SessionKiosk
import com.maxleveldetox.ui.DashboardActivity

/**
 * MainActivity — Native entry point and router.
 * Redirects seamlessly to DashboardActivity while preserving notification extras.
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val target = Intent(this, DashboardActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (intent.extras != null) {
                putExtras(intent.extras!!)
            }
        }
        startActivity(target)
        finish()
    }

    override fun onResume() {
        super.onResume()
        SessionKiosk.onOwnAppResumed(this)
    }
}
