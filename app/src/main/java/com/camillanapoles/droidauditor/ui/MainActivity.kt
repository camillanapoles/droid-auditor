package com.camillanapoles.droidauditor.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.camillanapoles.droidauditor.AuditApp
import com.camillanapoles.droidauditor.ui.nav.AppNav
import com.camillanapoles.droidauditor.ui.theme.AppTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as AuditApp).container
        setContent {
            AppTheme {
                AppNav(container)
            }
        }
    }
}
