package io.github.ryoaonetsuki.mira.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.github.ryoaonetsuki.mira.data.local.MiraDatabase
import io.github.ryoaonetsuki.mira.ui.components.AppDrawer
import io.github.ryoaonetsuki.mira.ui.components.AppTopBar
import io.github.ryoaonetsuki.mira.ui.screens.*
import io.github.ryoaonetsuki.mira.ui.screens.settings.*
import io.github.ryoaonetsuki.mira.ui.theme.LocalMiraPalette
import kotlinx.coroutines.launch

@Composable
fun MiraNavGraph(
    navController: NavHostController = rememberNavController()
) {
    val context = LocalContext.current
    val palette = LocalMiraPalette.current
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

    val db = remember { MiraDatabase.getInstance(context) }
    val unreadNotifsCount by db.notificationDao().getUnreadCount().collectAsState(initial = 0)

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route ?: Screen.Splash.route

    val isHomeRoute = currentRoute == Screen.Home.route

    if (isHomeRoute) {
        // Home gets its own Scaffold with top bar + drawer.
        // HomeScreen renders its own bottom navigation bar internally.
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                AppDrawer(
                    currentRoute = currentRoute,
                    onNavigate = { route ->
                        navController.navigate(route) {
                            launchSingleTop = true
                        }
                    },
                    onCloseDrawer = {
                        scope.launch { drawerState.close() }
                    }
                )
            }
        ) {
            Scaffold(
                topBar = {
                    AppTopBar(
                        unreadNotifsCount = unreadNotifsCount,
                        onMenuClick = {
                            scope.launch { drawerState.open() }
                        },
                        onNotificationsClick = {
                            navController.navigate(Screen.Notifications.route)
                        },
                        onAvatarClick = {
                            navController.navigate(Screen.About.route)
                        }
                    )
                },
                containerColor = palette.background
            ) { padding ->
                // HomeScreen fills the full scaffold area including padding.
                // Its internal Column adds the bottom nav bar at the bottom.
                HomeScreen(
                    onNavigate = { route ->
                        navController.navigate(route)
                    }
                )
            }
        }
    } else {
        NavHost(
            navController = navController,
            startDestination = Screen.Splash.route,
            modifier = Modifier
                .fillMaxSize()
                .background(palette.background)
        ) {
            composable(Screen.Splash.route) {
                SplashScreen(
                    onNavigate = { targetRoute ->
                        navController.navigate(targetRoute) {
                            popUpTo(Screen.Splash.route) { inclusive = true }
                        }
                    }
                )
            }

            composable(
                route = Screen.Setup.route,
                arguments = listOf(
                    navArgument("startStep") {
                        type = NavType.IntType
                        defaultValue = 1
                    }
                )
            ) { backStackEntry ->
                val startStep = backStackEntry.arguments?.getInt("startStep") ?: 1
                SetupScreen(
                    startStep = startStep,
                    onSetupComplete = {
                        navController.navigate(Screen.Home.route) {
                            popUpTo(Screen.Setup.route) { inclusive = true }
                        }
                    }
                )
            }

            composable(Screen.Home.route) {
                // Handled in parent branch when route matches
                HomeScreen(
                    onNavigate = { route -> navController.navigate(route) }
                )
            }

            composable(Screen.Chat.route) {
                ChatScreen(
                    onBack = { navController.popBackStack() },
                    onOpenBrowser = { url ->
                        navController.navigate("browser?url=${java.net.URLEncoder.encode(url, "UTF-8")}")
                    }
                )
            }

            composable(Screen.Scan.route) {
                ScanScreen(onBack = { navController.popBackStack() })
            }

            composable(Screen.Memories.route) {
                MemoriesScreen(onBack = { navController.popBackStack() })
            }

            composable(Screen.Rules.route) {
                RulesScreen(onBack = { navController.popBackStack() })
            }

            composable(Screen.Documents.route) {
                DocumentsScreen(onBack = { navController.popBackStack() })
            }

            composable(Screen.Photos.route) {
                PhotosScreen(onBack = { navController.popBackStack() })
            }

            composable(Screen.CodeProjects.route) {
                CodeProjectsScreen(
                    onBack = { navController.popBackStack() },
                    onOpenBrowser = { url ->
                        navController.navigate("browser?url=${java.net.URLEncoder.encode(url, "UTF-8")}")
                    }
                )
            }

            composable(Screen.Whiteboard.route) {
                WhiteboardScreen(onBack = { navController.popBackStack() })
            }

            composable(Screen.Vibes.route) {
                VibesScreen(onBack = { navController.popBackStack() })
            }

            composable(Screen.Markets.route) {
                MarketsScreen(onBack = { navController.popBackStack() })
            }

            composable(Screen.Marketing.route) {
                MarketingScreen(onBack = { navController.popBackStack() })
            }

            composable(Screen.Licence.route) {
                LicenceScreen(
                    onLicenceSuccess = {
                        navController.navigate(Screen.Home.route) {
                            popUpTo(Screen.Licence.route) { inclusive = true }
                        }
                    },
                    onBack = { navController.popBackStack() }
                )
            }

            composable(Screen.Extensions.route) {
                ExtensionsScreen(onBack = { navController.popBackStack() })
            }

            composable(Screen.ImageGen.route) {
                ImageGenScreen(onBack = { navController.popBackStack() })
            }

            composable(Screen.WebSearch.route) {
                WebSearchScreen(
                    onBack = { navController.popBackStack() },
                    onOpenUrl = { url ->
                        navController.navigate("browser?url=${java.net.URLEncoder.encode(url, "UTF-8")}")
                    }
                )
            }

            composable(Screen.Browser.route) {
                BrowserScreen(onBack = { navController.popBackStack() })
            }

            composable("browser?url={url}") { backStackEntry ->
                val encodedUrl = backStackEntry.arguments?.getString("url")
                val url = if (!encodedUrl.isNullOrBlank()) {
                    try { java.net.URLDecoder.decode(encodedUrl, "UTF-8") } catch (_: Exception) { encodedUrl }
                } else "https://duckduckgo.com"
                BrowserScreen(initialUrl = url, onBack = { navController.popBackStack() })
            }

            composable(Screen.Notifications.route) {
                NotificationsScreen(onBack = { navController.popBackStack() })
            }

            composable(Screen.Update.route) {
                UpdateScreen(onBack = { navController.popBackStack() })
            }

            // Settings Routes
            composable(Screen.SettingsHub.route) {
                SettingsHubScreen(
                    onNavigate = { route -> navController.navigate(route) },
                    onBack = { navController.popBackStack() }
                )
            }

            composable(Screen.SettingsMaya.route) {
                MayaSettingsScreen(
                    onNavigate = { route -> navController.navigate(route) },
                    onBack = { navController.popBackStack() }
                )
            }

            composable(Screen.SettingsPersonal.route) {
                PersonalSettingsScreen(onBack = { navController.popBackStack() })
            }

            composable(Screen.SettingsSecret.route) {
                SecretSettingsScreen(onBack = { navController.popBackStack() })
            }

            composable(Screen.SettingsVoice.route) {
                VoiceEngineScreen(onBack = { navController.popBackStack() })
            }

            composable(Screen.SettingsAdvanced.route) {
                AdvancedSettingsScreen(onBack = { navController.popBackStack() })
            }

            composable(Screen.SettingsConnectors.route) {
                ConnectorsSettingsScreen(onBack = { navController.popBackStack() })
            }

            composable(Screen.SettingsEmail.route) {
                EmailSettingsScreen(onBack = { navController.popBackStack() })
            }

            composable(Screen.SettingsMessages.route) {
                MessagesSettingsScreen(onBack = { navController.popBackStack() })
            }

            composable(Screen.SettingsSocial.route) {
                SocialSettingsScreen(onBack = { navController.popBackStack() })
            }

            composable(Screen.SettingsOptional.route) {
                OptionalSettingsScreen(onBack = { navController.popBackStack() })
            }

            composable(Screen.SettingsSkills.route) {
                SkillsSettingsScreen(onBack = { navController.popBackStack() })
            }

            composable(Screen.SettingsBackup.route) {
                BackupSettingsScreen(onBack = { navController.popBackStack() })
            }

            composable(Screen.About.route) {
                AboutScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}
