package io.github.ryoaonetsuki.mira.ui.navigation

sealed class Screen(val route: String) {
    object Splash : Screen("splash")
    object Setup : Screen("setup?startStep={startStep}") {
        fun createRoute(startStep: Int = 1) = "setup?startStep=$startStep"
    }
    object Home : Screen("home")
    object Chat : Screen("chat")
    object Scan : Screen("scan")
    object Memories : Screen("memories")
    object Rules : Screen("rules")
    object CodeProjects : Screen("code_projects")
    object Whiteboard : Screen("whiteboard")
    object Vibes : Screen("vibes")
    object Markets : Screen("markets")
    object Marketing : Screen("marketing")
    object Licence : Screen("licence")
    object Extensions : Screen("extensions")
    object Browser : Screen("browser")
    object Notifications : Screen("notifications")
    object Update : Screen("update")

    // Settings hub and sub-screens
    object SettingsHub : Screen("settings")
    object SettingsMaya : Screen("settings_maya")
    object SettingsPersonal : Screen("settings_personal")
    object SettingsSecret : Screen("settings_secret")
    object SettingsVoice : Screen("settings_voice")
    object SettingsAdvanced : Screen("settings_advanced")
    object SettingsConnectors : Screen("settings_connectors")
    object SettingsEmail : Screen("settings_email")
    object SettingsMessages : Screen("settings_messages")
    object SettingsSocial : Screen("settings_social")
    object SettingsOptional : Screen("settings_optional")
    object SettingsSkills : Screen("settings_skills")
    object SettingsBackup : Screen("settings_backup")
    object About : Screen("about")
}
