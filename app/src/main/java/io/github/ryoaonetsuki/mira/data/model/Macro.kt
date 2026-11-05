package io.github.ryoaonetsuki.mira.data.model

import android.content.Context
import android.content.Intent
import android.net.Uri

data class MacroItem(
    val id: String,
    val title: String,
    val description: String,
    val paramPrompt: String,
    val defaultParam: String,
    val actionType: MacroActionType
)

enum class MacroActionType {
    WHATSAPP,
    YOUTUBE,
    MAPS,
    CALL,
    SMS,
    WEB_SEARCH
}

object MacroCatalog {
    val builtinMacros = listOf(
        MacroItem(
            id = "macro_whatsapp",
            title = "Send WhatsApp Message",
            description = "Quickly send a pre-filled WhatsApp message via wa.me link",
            paramPrompt = "Phone number with country code (e.g. +1234567890)",
            defaultParam = "+1234567890",
            actionType = MacroActionType.WHATSAPP
        ),
        MacroItem(
            id = "macro_youtube",
            title = "Play Song on YouTube",
            description = "Search and stream music or podcasts on YouTube",
            paramPrompt = "Search query or song title",
            defaultParam = "Lofi hip hop radio beats to relax",
            actionType = MacroActionType.YOUTUBE
        ),
        MacroItem(
            id = "macro_maps",
            title = "Open Maps Navigation",
            description = "Get instant turn-by-turn directions to any location",
            paramPrompt = "Destination address or place name",
            defaultParam = "Nearest coffee shop",
            actionType = MacroActionType.MAPS
        ),
        MacroItem(
            id = "macro_call",
            title = "Call Contact",
            description = "Directly dial a phone number",
            paramPrompt = "Phone number to call",
            defaultParam = "",
            actionType = MacroActionType.CALL
        ),
        MacroItem(
            id = "macro_sms",
            title = "Send SMS",
            description = "Draft and send a text message via default messaging app",
            paramPrompt = "Recipient phone number",
            defaultParam = "",
            actionType = MacroActionType.SMS
        ),
        MacroItem(
            id = "macro_search",
            title = "Search the Web",
            description = "Perform a private web search on DuckDuckGo",
            paramPrompt = "Search query",
            defaultParam = "Latest science news",
            actionType = MacroActionType.WEB_SEARCH
        )
    )

    fun executeMacro(context: Context, macro: MacroItem, param: String, extraMessage: String = "") {
        try {
            when (macro.actionType) {
                MacroActionType.WHATSAPP -> {
                    val cleanNumber = param.replace(Regex("[^0-9]"), "")
                    val encodedMsg = Uri.encode(extraMessage.ifBlank { "Hello from Mira AI" })
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$cleanNumber?text=$encodedMsg"))
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    context.startActivity(intent)
                }
                MacroActionType.YOUTUBE -> {
                    val query = Uri.encode(param)
                    val appIntent = Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube://results?search_query=$query"))
                    appIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    try {
                        context.startActivity(appIntent)
                    } catch (e: Exception) {
                        val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=$query"))
                        webIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        context.startActivity(webIntent)
                    }
                }
                MacroActionType.MAPS -> {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(param)}"))
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    context.startActivity(intent)
                }
                MacroActionType.CALL -> {
                    val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(param)}"))
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    context.startActivity(intent)
                }
                MacroActionType.SMS -> {
                    val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(param)}"))
                    intent.putExtra("sms_body", extraMessage.ifBlank { "Hello from Mira AI" })
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    context.startActivity(intent)
                }
                MacroActionType.WEB_SEARCH -> {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://duckduckgo.com/?q=${Uri.encode(param)}"))
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    context.startActivity(intent)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
