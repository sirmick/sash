package s1m.hwfido2provider.vault

import android.app.assist.AssistStructure
import android.text.InputType
import android.view.View
import android.view.autofill.AutofillId


/**
 * The username and password fields found in a form, and the site they belong to.
 *
 * [domain] comes from `ViewNode.getWebDomain()`, which the engine sets for web
 * content. That is what makes filling into a pane app work at all: the fields
 * are inside a rendered page, not native views, so the package name says
 * "com.pane" for every site and only the web domain distinguishes chase.com
 * from anything else.
 */
data class FormFields(
    val username: AutofillId? = null,
    val password: AutofillId? = null,
    val domain: String? = null
) {
    val fillable: Boolean get() = username != null || password != null

    val ids: Array<AutofillId> get() = listOfNotNull(username, password).toTypedArray()
}

object FormScanner {
    /**
     * Walks the view tree for something worth filling.
     *
     * Three signals, in order of trust: the autofill hints a page declares, the
     * HTML input type, and finally the input type flags. Pages that declare
     * nothing are common enough that the last two are not optional.
     */
    /**
     * @param focusedId the field the user is in, from `FillContext.getFocusedId()`.
     *   GeckoView does not set `ViewNode.isFocused` on its virtual nodes, so
     *   asking the node is not an option — and without it we match the wrong
     *   form on any page carrying more than one.
     */
    fun scan(structure: AssistStructure, focusedId: AutofillId? = null): FormFields {
        val domain = domainOf(structure)

        // Widen outwards from the focused field until a username/password pair
        // appears, and use that form.
        //
        // Taking the first pair in document order is wrong on any real page:
        // chase.com's home page carries more than one form, and doing so
        // produced a dataset for fields i5/i3 while the user was focused on
        // i15. Android only offers a dataset that covers the focused view, so
        // the fill was correct, complete, and invisible.
        val path = focusPath(structure, focusedId)
        for (i in path.indices.reversed()) {
            val found = walk(path[i], FormFields())
            if (found.username != null && found.password != null) {
                return found.copy(domain = domain)
            }
        }
        // A form with only one of the two — a username-first login, say — is
        // still worth filling, so fall back to whatever the focused subtree has.
        path.lastOrNull()?.let { focused ->
            val found = walk(focused, FormFields())
            if (found.fillable) return found.copy(domain = domain)
        }

        var whole = FormFields()
        for (i in 0 until structure.windowNodeCount) {
            whole = walk(structure.getWindowNodeAt(i).rootViewNode, whole)
        }
        return whole.copy(domain = domain)
    }

    /** Root-to-focused-node chain, or empty when nothing claims focus. */
    private fun focusPath(structure: AssistStructure, focusedId: AutofillId?): List<AssistStructure.ViewNode> {
        for (i in 0 until structure.windowNodeCount) {
            val path = mutableListOf<AssistStructure.ViewNode>()
            if (descend(structure.getWindowNodeAt(i).rootViewNode, focusedId, path)) return path
        }
        return emptyList()
    }

    private fun descend(
        node: AssistStructure.ViewNode,
        focusedId: AutofillId?,
        path: MutableList<AssistStructure.ViewNode>
    ): Boolean {
        path.add(node)
        if (if (focusedId != null) node.autofillId == focusedId else node.isFocused) return true
        for (i in 0 until node.childCount) {
            if (descend(node.getChildAt(i), focusedId, path)) return true
        }
        path.removeAt(path.size - 1)
        return false
    }

    /**
     * The document's domain, taken from the whole tree rather than the form's
     * subtree: it is carried on the node that owns the document, which is
     * usually an ancestor of every form on the page.
     */
    private fun domainOf(structure: AssistStructure): String? {
        for (i in 0 until structure.windowNodeCount) {
            findDomain(structure.getWindowNodeAt(i).rootViewNode)?.let { return it }
        }
        return null
    }

    private fun findDomain(node: AssistStructure.ViewNode): String? {
        node.webDomain?.takeIf { it.isNotBlank() }?.let { return it }
        for (i in 0 until node.childCount) {
            findDomain(node.getChildAt(i))?.let { return it }
        }
        return null
    }

    /** Dumps what the engine actually handed us, for when nothing matches. */
    fun describe(structure: AssistStructure) {
        android.util.Log.v("latch", "windows=${structure.windowNodeCount} for ${structure.activityComponent}")
        for (i in 0 until structure.windowNodeCount) {
            val w = structure.getWindowNodeAt(i)
            android.util.Log.v("latch", "window $i title='${w.title}' ${w.width}x${w.height}")
            describeNode(w.rootViewNode, 0)
        }
    }

    private fun describeNode(node: AssistStructure.ViewNode, depth: Int) {
        android.util.Log.v(
            "latch",
            "  ".repeat(depth) +
                "cls=${node.className} kids=${node.childCount} type=${node.autofillType} " +
                "hints=${node.autofillHints?.joinToString()} " +
                "html=${node.htmlInfo?.tag}:${node.htmlInfo?.attributes?.joinToString { it.first + "=" + it.second }} " +
                "domain=${node.webDomain} inputType=${node.inputType} hint=${node.hint} id=${node.idEntry}"
        )
        for (i in 0 until node.childCount) describeNode(node.getChildAt(i), depth + 1)
    }

    private fun walk(node: AssistStructure.ViewNode, acc: FormFields): FormFields {
        var found = acc
        val id = node.autofillId
        if (id != null && node.autofillType != View.AUTOFILL_TYPE_NONE) {
            when {
                isPassword(node) -> if (found.password == null) found = found.copy(password = id)
                isUsername(node) -> if (found.username == null) found = found.copy(username = id)
            }
        }

        for (i in 0 until node.childCount) {
            found = walk(node.getChildAt(i), found)
        }
        return found
    }

    private fun isPassword(node: AssistStructure.ViewNode): Boolean {
        if (node.autofillHints?.any { it.equalsIgnoreCase(View.AUTOFILL_HINT_PASSWORD) } == true) {
            return true
        }
        if (htmlType(node) == "password") return true
        val variation = node.inputType and InputType.TYPE_MASK_VARIATION
        return node.inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_TEXT &&
            (
                variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                )
    }

    private fun isUsername(node: AssistStructure.ViewNode): Boolean {
        if (node.autofillHints?.any {
                it.equalsIgnoreCase(View.AUTOFILL_HINT_USERNAME) ||
                    it.equalsIgnoreCase(View.AUTOFILL_HINT_EMAIL_ADDRESS)
            } == true
        ) {
            return true
        }
        if (htmlType(node) in setOf("email", "text", "tel")) return true
        val variation = node.inputType and InputType.TYPE_MASK_VARIATION
        return variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
    }

    private fun htmlType(node: AssistStructure.ViewNode): String? =
        node.htmlInfo?.takeIf { it.tag.equalsIgnoreCase("input") }
            ?.attributes
            ?.firstOrNull { it.first.equalsIgnoreCase("type") }
            ?.second
            ?.lowercase()

    private fun String.equalsIgnoreCase(other: String) = this.equals(other, ignoreCase = true)
}
