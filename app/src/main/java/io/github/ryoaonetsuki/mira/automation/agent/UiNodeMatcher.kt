package io.github.ryoaonetsuki.mira.automation.agent

/**
 * Fluent element matching over Mira's already-dumped [ScreenNode] tree.
 *
 * Ported matching semantics from flutter_automate's UiSelector
 * (jiusanzhou/flutter_automate, MIT © 2026 Zoe (jiusanzhou)),
 * android/.../core/UiSelector.kt `matches()`: a node matches by text,
 * viewId (resource-id), className, or contentDescription, each with
 * equals / contains / startsWith / endsWith rules, plus state flags.
 *
 * Adapted, not copied verbatim:
 * - Mira matches on the dumped [ScreenNode] list (no live
 *   AccessibilityNodeInfo tree walk, no `waitFor` polling loop).
 * - Text/description matching stays case-insensitive (Mira's clickText
 *   contract); viewId/className keep UiSelector's case-sensitive rules.
 * - The `:id/` suffix rule (UiSelector idEquals: `nodeId.endsWith(":id/$it")`)
 *   and the `android.widget.<ShortName>` class rule (UiSelector
 *   classNameEquals) are kept exactly, because app layouts use both forms.
 *
 * Used by [ScreenAutomationService.clickText] as the fallback chain when
 * the plain text/description match finds nothing: a plan step may carry a
 * viewId or class name instead of visible text.
 */
object UiNodeMatcher {

    /** Visible text or content description contains [query] (case-insensitive). */
    fun findByText(nodes: List<ScreenNode>, query: String): ScreenNode? {
        if (query.isBlank()) return null
        return nodes.firstOrNull { node ->
            node.isClickable && (
                node.text.contains(query, ignoreCase = true) ||
                    node.contentDescription.contains(query, ignoreCase = true)
                )
        } ?: nodes.firstOrNull { node ->
            node.text.contains(query, ignoreCase = true) ||
                node.contentDescription.contains(query, ignoreCase = true)
        }
    }

    /**
     * ViewId match (UiSelector idEquals): exact, or the short form after
     * the `:id/` prefix — e.g. query "search_button" matches
     * "com.example.app:id/search_button".
     */
    fun findByViewId(nodes: List<ScreenNode>, query: String): ScreenNode? {
        if (query.isBlank()) return null
        return nodes.firstOrNull { node ->
            val id = node.viewId
            id.isNotBlank() && (id == query || id.endsWith(":id/$query"))
        }
    }

    /**
     * Class match (UiSelector classNameEquals): exact, the `android.widget.`
     * short form, or a `.ShortName` suffix — e.g. query "Button" matches
     * "android.widget.Button".
     */
    fun findByClass(nodes: List<ScreenNode>, query: String): ScreenNode? {
        if (query.isBlank()) return null
        return nodes.firstOrNull { node ->
            val name = node.className
            name.isNotBlank() && (
                name == query ||
                    name.endsWith(".$query") ||
                    name == "android.widget.$query"
                )
        }
    }

    /**
     * The fallback chain: visible text/description first, then viewId, then
     * class name. Returns null when nothing on screen matches [query] in any
     * form.
     */
    fun findBest(nodes: List<ScreenNode>, query: String): ScreenNode? =
        findByText(nodes, query)
            ?: findByViewId(nodes, query)
            ?: findByClass(nodes, query)
}
