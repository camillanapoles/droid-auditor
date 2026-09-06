package com.camillanapoles.droidauditor.ui.nav

import android.net.Uri
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.camillanapoles.droidauditor.R
import com.camillanapoles.droidauditor.di.AppContainer
import com.camillanapoles.droidauditor.ui.screens.AuditScreen
import com.camillanapoles.droidauditor.ui.screens.CommandsScreen
import com.camillanapoles.droidauditor.ui.screens.DashboardScreen
import com.camillanapoles.droidauditor.ui.screens.ExplorerScreen
import com.camillanapoles.droidauditor.ui.screens.OptimizeScreen
import com.camillanapoles.droidauditor.ui.screens.OutputViewerScreen
import com.camillanapoles.droidauditor.ui.screens.PackageDetailScreen
import com.camillanapoles.droidauditor.ui.screens.TimelineScreen

private data class TopLevel(val route: String, val labelRes: Int, val icon: ImageVector)

@Composable
fun AppNav(container: AppContainer) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    val topLevel = listOf(
        TopLevel("dashboard", R.string.nav_dashboard, Icons.Filled.Home),
        TopLevel("audit", R.string.nav_audit, Icons.Filled.List),
        TopLevel("explorer", R.string.nav_explorer, Icons.Filled.Search),
        TopLevel("timeline", R.string.nav_timeline, Icons.Filled.DateRange),
        TopLevel("optimize", R.string.nav_optimize, Icons.Filled.Build),
        TopLevel("commands", R.string.nav_commands, Icons.Filled.Settings)
    )

    Scaffold(
        bottomBar = {
            if (topLevel.any { it.route == currentRoute }) {
                NavigationBar {
                    topLevel.forEach { item ->
                        NavigationBarItem(
                            selected = currentRoute == item.route,
                            onClick = {
                                navController.navigate(item.route) {
                                    popUpTo(navController.graph.startDestinationId) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                Icon(
                                    imageVector = item.icon,
                                    contentDescription = stringResource(item.labelRes)
                                )
                            },
                            label = { Text(stringResource(item.labelRes)) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "dashboard",
            modifier = Modifier.padding(padding)
        ) {
            composable("dashboard") {
                DashboardScreen(container)
            }
            composable("audit") {
                AuditScreen(
                    container,
                    onOpenOutput = { path -> navController.navigate("output/" + Uri.encode(path)) },
                    onOpenPackage = { runId, pkg ->
                        navController.navigate("package/$runId/" + Uri.encode(pkg))
                    }
                )
            }
            composable("explorer") {
                ExplorerScreen(
                    container,
                    onOpenPackage = { runId, pkg ->
                        navController.navigate("package/$runId/" + Uri.encode(pkg))
                    }
                )
            }
            composable("timeline") {
                TimelineScreen(container)
            }
            composable("optimize") {
                OptimizeScreen(container)
            }
            composable("commands") {
                CommandsScreen(
                    container,
                    onOpenOutput = { path -> navController.navigate("output/" + Uri.encode(path)) }
                )
            }
            composable(
                route = "output/{path}",
                arguments = listOf(navArgument("path") { type = NavType.StringType })
            ) { entry ->
                OutputViewerScreen(path = entry.arguments?.getString("path").orEmpty())
            }
            composable(
                route = "package/{runId}/{pkg}",
                arguments = listOf(
                    navArgument("runId") { type = NavType.LongType },
                    navArgument("pkg") { type = NavType.StringType }
                )
            ) { entry ->
                val runId = entry.arguments?.getLong("runId") ?: -1L
                val pkg = Uri.decode(entry.arguments?.getString("pkg").orEmpty())
                PackageDetailScreen(container, runId, pkg, onBack = { navController.popBackStack() })
            }
        }
    }
}
