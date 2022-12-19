package org.modelix.editor

import kotlinx.html.TagConsumer
import kotlinx.html.classes
import kotlinx.html.div
import kotlinx.html.table
import kotlinx.html.td
import kotlinx.html.tr

class CodeCompletionMenu(val editor: EditorComponent, val providers: List<ICodeCompletionActionProvider>) : IProducesHtml {
    private var pattern: String = ""
    private var selectedIndex: Int = 0
    private var entries: List<ICodeCompletionAction> = emptyList()

    fun updateActions() {
        val parameters = parameters()
        entries = providers.flatMap { it.getActions(parameters) }.filter { it.isApplicable(parameters) }
    }

    private fun parameters() = CodeCompletionParameters(editor, pattern)

    fun selectNext() {
        selectedIndex++
        if (selectedIndex >= entries.size) selectedIndex = 0
    }

    fun selectPrevious() {
        selectedIndex--
        if (selectedIndex < 0) selectedIndex = (entries.size - 1).coerceAtLeast(0)
    }

    fun getSelectedEntry(): ICodeCompletionAction? = entries.getOrNull(selectedIndex)

    override fun <T> toHtml(consumer: TagConsumer<T>, produceChild: (IProducesHtml) -> T) {
        consumer.div("ccmenu") {
            table {
                val parameters = parameters()
                entries.forEachIndexed { index, action ->
                    tr("visibleSelection".takeIf { index == selectedIndex }) {
                        td("matchingText") {
                            +action.getMatchingText(parameters)
                        }
                        td("description") {
                            +action.getDescription(parameters)
                        }
                    }
                }
                if (entries.isEmpty()) {
                    tr {
                        td {
                            +"No matches found"
                        }
                    }
                }
            }
        }
    }
}

interface ICodeCompletionActionProvider {
    fun getActions(parameters: CodeCompletionParameters): List<ICodeCompletionAction>
}

interface ICodeCompletionAction {
    fun isApplicable(parameters: CodeCompletionParameters): Boolean
    fun getMatchingText(parameters: CodeCompletionParameters): String
    fun getDescription(parameters: CodeCompletionParameters): String
}

class CodeCompletionParameters(val editor: EditorComponent, val pattern: String)