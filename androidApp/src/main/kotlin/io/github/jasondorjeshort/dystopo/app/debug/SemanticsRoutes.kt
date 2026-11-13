package io.github.jasondorjeshort.dystopo.app.debug

import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.Request
import io.github.jasondorjeshort.dystopo.app.Response

/**
 * T270, the app's own accessibility tree (debug builds only, on the debug server), for `tools/android-a11y.py`:
 * - `GET /semantics[?tree=merged|unmerged|both]` (default both): every Compose root of the app's windows (the activity's and
 *   any popup's, found through the window manager's root views, else the map view's root), each with `rootClass`,
 *   `offsetPx` (where the root's origin lies on the screen) and its `merged` and `unmerged` semantics trees as flat lists in
 *   depth-first order (a later node draws over an earlier one). A node: `id`, `parent` (null at the root), `depth`,
 *   `boundsPx` and `touchBoundsPx` (left, top, right, bottom on the screen, as `uiautomator dump` and a screencap count; the
 *   bounds are clipped by the node's ancestors, so a node scrolled out of its list has an empty rectangle and one cut by the
 *   list's edge is smaller than `sizePx`, the node's own unclipped size; the touch bounds are Compose's `touchBoundsInRoot`,
 *   which a small `clickable` stretches to its 48 dp touch area outside its layout bounds; the dump shows that for some nodes
 *   and not for others: on API 35 a chip's dumped bounds were its touch bounds, a text link's its layout bounds), `text` (the
 *   node's texts joined by a space; in the merged tree its descendants' too), `editableText`, `contentDescription`, `role`,
 *   `clickable` (an `OnClick` action) and `longClickable`, `enabled` (no `Disabled`), `hidden` (`HideFromAccessibility` or
 *   `InvisibleToUser`), `transparent` (drawn at alpha 0, which Compose leaves out of what TalkBack reads), `placed` (the node
 *   and every ancestor laid out), `mergesDescendants`, `testTag`, `stateDescription`, `toggle`, `selected`, `heading`, and for
 *   a text node of the unmerged tree `textStyle` (`fontSizeSp`, `fontScale`, `sizeDp`, the drawn size, and `bold`, from its
 *   text layout), which the WCAG contrast rule needs to tell large text. Top level: `density`, `fontScale` (the system's,
 *   `settings put system font_scale`), `screenPx` and `ms`, the walk's time.
 *   503 when the activity is in the background (no window to read).
 */
object SemanticsRoutes {
    fun handle(app: DystopoApp, r: Request): Response? {
        if (r.method != "GET" || r.path != "/semantics") return null
        val tree = r.query["tree"] ?: "both"
        if (tree !in setOf("merged", "unmerged", "both")) return Response.json(mapOf("error" to "tree must be merged, unmerged or both"), 400)
        app.map.backgroundReason()?.let { why -> return Response.json(mapOf("error" to "the app is in the background", "reason" to why), 503) }
        return Response.json(app.map.onMain(10_000) { json(app, tree) })
    }

    /** Main thread. */
    fun json(app: DystopoApp, tree: String): Map<String, Any?> {
        val t0 = System.nanoTime()
        val res = app.resources
        val dm = res.displayMetrics
        val windows = ArrayList<Map<String, Any?>>()
        val seen = HashSet<Any>()
        for (root in rootViews(app)) {
            fun views(v: View) {
                if (v is RootForTest && seen.add(v)) windows += window(v, windows.size, tree)
                if (v is ViewGroup) for (i in 0 until v.childCount) views(v.getChildAt(i))
            }
            views(root)
        }
        return linkedMapOf(
            "density" to dm.density.toDouble(),
            "fontScale" to res.configuration.fontScale.toDouble(),
            "screenPx" to listOf(dm.widthPixels, dm.heightPixels),
            "windows" to windows,
            "ms" to (System.nanoTime() - t0) / 1_000_000.0,
        )
    }

    /** The window manager's root views (the activity's window and any popup's), else the map view's root. */
    private fun rootViews(app: DystopoApp): List<View> {
        val fromManager = runCatching {
            val c = Class.forName("android.view.WindowManagerGlobal")
            val global = c.getMethod("getInstance").invoke(null)
            val f = c.getDeclaredField("mViews").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            (f.get(global) as List<View>).filter { it.isAttachedToWindow && it.visibility == View.VISIBLE }.toList()
        }.getOrNull().orEmpty()
        return fromManager.ifEmpty { listOfNotNull(app.map.mapView?.rootView) }
    }

    private fun window(v: View, index: Int, tree: String): Map<String, Any?> {
        val owner = (v as RootForTest).semanticsOwner
        val view = v as View
        val loc = IntArray(2).also { view.getLocationOnScreen(it) }
        val out = linkedMapOf<String, Any?>(
            "index" to index, "rootClass" to view.javaClass.simpleName, "offsetPx" to listOf(loc[0], loc[1]),
            "sizePx" to listOf(view.width, view.height),
        )
        if (tree != "unmerged") out["merged"] = flat(owner.rootSemanticsNode, loc, merged = true)
        if (tree != "merged") out["unmerged"] = flat(owner.unmergedRootSemanticsNode, loc, merged = false)
        return out
    }

    private fun flat(root: SemanticsNode, loc: IntArray, merged: Boolean): List<Map<String, Any?>> {
        val out = ArrayList<Map<String, Any?>>()
        fun walk(n: SemanticsNode, parent: Int?, depth: Int) {
            out += node(n, parent, depth, loc, merged)
            n.children.forEach { walk(it, n.id, depth + 1) }
        }
        walk(root, null, 0)
        return out
    }

    private fun rect(l: Float, t: Float, r: Float, b: Float, dx: Float, dy: Float): List<Int> =
        listOf(Math.round(l + dx), Math.round(t + dy), Math.round(r + dx), Math.round(b + dy))

    @Suppress("DEPRECATION")
    private fun node(n: SemanticsNode, parent: Int?, depth: Int, loc: IntArray, merged: Boolean): Map<String, Any?> {
        val c = n.config
        val dx = loc[0].toFloat()
        val dy = loc[1].toFloat()
        val b = n.boundsInRoot
        val tb = n.touchBoundsInRoot
        val m = linkedMapOf<String, Any?>(
            "id" to n.id, "parent" to parent, "depth" to depth,
            "boundsPx" to rect(b.left, b.top, b.right, b.bottom, dx, dy),
            "touchBoundsPx" to rect(tb.left, tb.top, tb.right, tb.bottom, dx, dy),
            "sizePx" to listOf(n.size.width, n.size.height),
        )
        c.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }?.takeIf { it.isNotEmpty() }?.let { m["text"] = it }
        c.getOrNull(SemanticsProperties.EditableText)?.text?.let { m["editableText"] = it }
        c.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ")?.takeIf { it.isNotEmpty() }?.let { m["contentDescription"] = it }
        c.getOrNull(SemanticsProperties.Role)?.let { m["role"] = it.toString() }
        m["clickable"] = c.getOrNull(SemanticsActions.OnClick) != null
        m["longClickable"] = c.getOrNull(SemanticsActions.OnLongClick) != null
        m["enabled"] = !c.contains(SemanticsProperties.Disabled)
        m["hidden"] = c.contains(SemanticsProperties.HideFromAccessibility) || c.contains(SemanticsProperties.InvisibleToUser)
        m["placed"] = generateSequence(n.layoutInfo) { it.parentInfo }.all { it.isPlaced }
        transparent(n)?.let { m["transparent"] = it }
        m["mergesDescendants"] = c.isMergingSemanticsOfDescendants
        c.getOrNull(SemanticsProperties.TestTag)?.let { m["testTag"] = it }
        c.getOrNull(SemanticsProperties.StateDescription)?.let { m["stateDescription"] = it }
        c.getOrNull(SemanticsProperties.ToggleableState)?.let { m["toggle"] = it.toString() }
        c.getOrNull(SemanticsProperties.Selected)?.let { m["selected"] = it }
        if (c.contains(SemanticsProperties.Heading)) m["heading"] = true
        if (!merged && m["text"] != null) textStyle(n)?.let { m["textStyle"] = it }
        return m
    }

    /** Compose's own `isTransparent` (drawn at alpha 0, left out of the accessibility tree), read by reflection: internal to ui. */
    private val isTransparent = runCatching { SemanticsNode::class.java.getMethod("isTransparent\$ui_release") }.getOrNull()

    private fun transparent(n: SemanticsNode): Boolean? = isTransparent?.let { m -> runCatching { m.invoke(n) as Boolean }.getOrNull() }

    /** The text's size and weight from its layout (the action Compose's own tests read), or null when it has none. */
    private fun textStyle(n: SemanticsNode): Map<String, Any?>? = runCatching {
        val results = ArrayList<TextLayoutResult>()
        val ok = n.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results) ?: false
        val l = results.firstOrNull()?.layoutInput ?: return null
        if (!ok) return null
        val size = l.style.fontSize
        val sp = if (size.isSp) size.value.toDouble() else null
        val weight = l.style.fontWeight ?: FontWeight.Normal
        linkedMapOf(
            "fontSizeSp" to sp, "fontScale" to l.density.fontScale.toDouble(),
            "sizeDp" to sp?.let { it * l.density.fontScale },
            "bold" to (weight.weight >= FontWeight.Bold.weight),
        )
    }.getOrNull()
}
