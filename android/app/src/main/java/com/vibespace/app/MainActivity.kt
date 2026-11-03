package com.vibespace.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.NavHostController
import com.vibespace.app.ui.VibeSpaceRoot
import com.vibespace.app.ui.theme.VibeSpaceTheme

/** AppCompatActivity (a ComponentActivity) so per-app language switching works on every API level. */
class MainActivity : AppCompatActivity() {
    private var navController: NavHostController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            VibeSpaceTheme {
                VibeSpaceRoot(onNavController = { navController = it })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        navController?.handleDeepLink(intent)
    }
}
