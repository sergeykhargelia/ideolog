package com.intellij.ideolog.foldings

import com.intellij.ideolog.highlighting.settings.LogHighlightingSettingsStore
import com.intellij.ideolog.lex.LogToken
import com.intellij.ideolog.lex.detectLogFileFormat
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.FoldRegion
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import java.util.*

val hiddenItemsKey = Key.create<HashSet<Pair<Int, String>>>("Log.HiddenColumnValues")

class FoldingCalculatorTask(project: Project, val editor: Editor, fileName: String) : Task.Backgroundable(project, "Calculating foldings for $fileName", true) {
  val foldings = ArrayList<Pair<Int, Int>>()
  val settings = LogHighlightingSettingsStore.getInstance()
  val hiddenItems = editor.document.getUserData(hiddenItemsKey) ?: emptySet<Pair<Int, String>>()
  val fileType = detectLogFileFormat(editor)
  val tokens = ArrayList<LogToken>()
  var lastAddedFoldingEndOffset = 0
  var myCancel = false

  override fun run(indicator: ProgressIndicator) {
    val document = editor.document
    ApplicationManager.getApplication().invokeAndWait {
      editor.foldingModel.runBatchFoldingOperation {
      }
    }

    var lastVisibleLine = -1
    var lastLineWasVisible = false
    var i = 0
    while (i < document.lineCount) {
      if (indicator.isCanceled || myCancel)
        return
      indicator.fraction = i / document.lineCount.toDouble()
      val start = document.getLineStartOffset(i)
      val end = document.getLineEndOffset(i)
      val line = document.charsSequence.subSequence(start, end)

      if (if (fileType.isLineEventStart(line)) isLineVisible(line) else lastLineWasVisible) {
        lastLineWasVisible = true
        if (i - lastVisibleLine > 1) {
          foldings.add(lastVisibleLine + 1 to i - 1)
          if (foldings.size >= 100) {
            ApplicationManager.getApplication().invokeAndWait {
              editor.foldingModel.runBatchFoldingOperation({
                val lastNewFoldingOffset = editor.document.getLineEndOffset(foldings.last().second)
                val allFoldings = editor.foldingModel.allFoldRegions
                allFoldings.forEach {
                  if (it.startOffset > lastAddedFoldingEndOffset) {
                    if (it.startOffset > lastNewFoldingOffset)
                      return@forEach
                    editor.foldingModel.removeFoldRegion(it)
                  }
                }

                val addedFoldings = ArrayList<FoldRegion?>()

                foldings.forEach {
                  addedFoldings.add(editor.foldingModel.addFoldRegion(editor.document.getLineStartOffset(it.first), editor.document.getLineEndOffset(it.second), " ... ${it.second - it.first + 1} lines hidden ... "))
                }

                addedFoldings.forEach {
                  it?.isExpanded = false
                }

                lastAddedFoldingEndOffset = lastNewFoldingOffset
              }, true)
              foldings.clear()
            }
          }
        }
        lastVisibleLine = i
      } else {
        lastLineWasVisible = false
      }
      i++
    }
    if (document.lineCount - lastVisibleLine > 1)
      foldings.add(lastVisibleLine + 1 to document.lineCount - 1)
  }

  override fun onSuccess() {
    editor.foldingModel.runBatchFoldingOperation({
      val lastNewFoldingOffset = editor.document.charsSequence.length
      val allFoldings = editor.foldingModel.allFoldRegions

      allFoldings.forEach {
        if (it.startOffset > lastAddedFoldingEndOffset) {
          if (it.startOffset > lastNewFoldingOffset)
            return@forEach
          editor.foldingModel.removeFoldRegion(it)
        }
      }

      val addedFoldings = ArrayList<FoldRegion?>()

      foldings.forEach {
        addedFoldings.add(editor.foldingModel.addFoldRegion(editor.document.getLineStartOffset(it.first), editor.document.getLineEndOffset(it.second), " ... ${it.second - it.first + 1} lines hidden ... "))
      }

      addedFoldings.forEach {
        it?.isExpanded = false
      }
    }, true)
  }

  override fun shouldStartInBackground() = true

  private fun isLineVisible(line: CharSequence): Boolean {
    @Suppress("LoopToCallChain")
    for (pattern in settings.myState.hidden)
      if (line.contains(pattern, true))
        return false

    if (hiddenItems.isNotEmpty()) {
      tokens.clear()
      fileType.tokenize(line, tokens, true)
      hiddenItems.forEach { (first, second) ->
        if (first < tokens.size && second == tokens[first].takeFrom(line).toString())
          return false
      }
    }

    return true
  }
}