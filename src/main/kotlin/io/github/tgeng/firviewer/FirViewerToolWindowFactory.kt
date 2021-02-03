package io.github.tgeng.firviewer

import com.google.common.cache.CacheBuilder
import com.intellij.AppTopics
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.*
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.FirPureAbstractElement
import org.jetbrains.kotlin.fir.visitors.FirVisitorVoid
import org.jetbrains.kotlin.idea.fir.low.level.api.api.getFirFile
import org.jetbrains.kotlin.idea.fir.low.level.api.api.getResolveState
import org.jetbrains.kotlin.psi.KtFile

class FirViewerToolWindowFactory : ToolWindowFactory, DumbAware {

    private val cache = CacheBuilder.newBuilder().weakKeys().build<PsiFile, TreeUiState>()

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
//    System.setProperty("org.graphstream.ui", "swing")
        toolWindow.title = "FirViewer"
        toolWindow.setIcon(AllIcons.Toolwindows.ToolWindowHierarchy)
        toolWindow.setTitleActions(listOf(object : AnAction(), DumbAware {
            override fun update(e: AnActionEvent) {
                e.presentation.icon = AllIcons.Actions.Refresh
            }

            override fun actionPerformed(e: AnActionEvent) {
                refresh(project, toolWindow)
            }
        }))

        fun refresh() = ApplicationManager.getApplication().invokeLater {
            if (!toolWindow.isVisible) return@invokeLater
            refresh(project, toolWindow)
        }
        project.messageBus.connect().apply {
            subscribe(AppTopics.FILE_DOCUMENT_SYNC, object : FileDocumentManagerListener {
                override fun fileContentLoaded(file: VirtualFile, document: Document) {
                    refresh()
                    document.addDocumentListener(object : DocumentListener {
                        override fun documentChanged(event: DocumentEvent) {
                            refresh()
                        }

                        override fun bulkUpdateFinished(document: Document) {
                            refresh()
                        }
                    })
                }
            })
            subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
                override fun fileOpened(source: FileEditorManager, file: VirtualFile) = refresh()
                override fun fileClosed(source: FileEditorManager, file: VirtualFile) = refresh()
                override fun selectionChanged(event: FileEditorManagerEvent) = refresh()
            })
        }
    }

    private fun refresh(project: Project, toolWindow: ToolWindow) {
        val vf = FileEditorManager.getInstance(project).selectedFiles.firstOrNull() ?: return
        val ktFile = PsiManager.getInstance(project).findFile(vf) as? KtFile ?: return
        val treeUiState = cache.get(ktFile) {
            val treeModel = ObjectTreeModel(
                ktFile,
                FirPureAbstractElement::class,
                { it.getFirFile(it.getResolveState()) }) { consumer ->
                acceptChildren(object : FirVisitorVoid() {
                    override fun visitElement(element: FirElement) {
                        if (element is FirPureAbstractElement) consumer(element)
                    }
                })
            }

            treeModel.setupTreeUi(project)
        }
        treeUiState.refreshTree()

        if (toolWindow.contentManager.contents.firstOrNull() != treeUiState.pane) {
            toolWindow.contentManager.removeAllContents(true)
            toolWindow.contentManager.addContent(
                toolWindow.contentManager.factory.createContent(
                    treeUiState.pane,
                    "Current File",
                    true
                )
            )
        }
    }
}
