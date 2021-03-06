package codehistoryminer.plugin

import codehistoryminer.core.lang.DateRange
import codehistoryminer.core.lang.Unscramble
import codehistoryminer.publicapi.analysis.Context
import codehistoryminer.publicapi.analysis.ContextLogger
import codehistoryminer.publicapi.analysis.Analyzer
import codehistoryminer.core.analysis.implementation.AnalyzerScriptLoader
import codehistoryminer.core.analysis.implementation.CombinedAnalyzer
import codehistoryminer.core.analysis.implementation.GroovyScript
import codehistoryminer.core.miner.MinedCommit
import codehistoryminer.plugin.historystorage.HistoryGrabberConfig
import codehistoryminer.plugin.historystorage.HistoryStorage
import codehistoryminer.plugin.historystorage.ScriptStorage
import codehistoryminer.plugin.ui.UI
import codehistoryminer.plugin.vcsaccess.VcsActions
import codehistoryminer.publicapi.lang.Cancelled
import codehistoryminer.publicapi.lang.Date
import codehistoryminer.publicapi.lang.Progress
import codehistoryminer.publicapi.lang.Time
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vcs.LocalFilePath
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.history.VcsFileRevision
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.indexing.FileBasedIndex
import groovy.time.TimeCategory
import liveplugin.PluginUtil

import static codehistoryminer.publicapi.analysis.filechange.FileChange.dateRangeBetween
import static codehistoryminer.publicapi.lang.Date.Formatter.dd_MM_yyyy
import static liveplugin.PluginUtil.invokeOnEDT

class CodeHistoryMinerPlugin {
	private final UI ui
	private final HistoryStorage historyStorage
	private final ScriptStorage scriptStorage
	private final VcsActions vcsAccess
	private final CodeHistoryMinerPluginLog log
	private volatile boolean grabHistoryIsInProgress

	CodeHistoryMinerPlugin(UI ui, HistoryStorage historyStorage, ScriptStorage scriptStorage,
	                       VcsActions vcsAccess, CodeHistoryMinerPluginLog log = null) {
		this.ui = ui
		this.historyStorage = historyStorage
		this.scriptStorage = scriptStorage
		this.vcsAccess = vcsAccess
		this.log = log
	}

	def runAnalyzer(File file, Project project, Analyzer analyzer, String analyzerName) {
		ui.runInBackground("Running ${analyzerName}") { ProgressIndicator indicator ->
			try {
				def projectName = historyStorage.guessProjectNameFrom(file.name)
				def cancelled = new Cancelled() {
					@Override boolean isTrue() { indicator.canceled }
				}
				def events = historyStorage.readAll(file.name, cancelled)
				if (events.empty) {
					return ui.showNoEventsInStorageMessage(file.name, project)
				}

				def context = new Context(cancelled).withLogger(new ContextLogger() {
					@Override void onLog(String message) { Logger.getInstance("CodeHistoryMining").info(message) }
				})
				context.progress.setListener(new Progress.Listener() {
					@Override void onUpdate(Progress progress) { indicator.fraction = progress.percentComplete() }
				})
				def result = analyzer.analyze(events, context)
				ui.showAnalyzerResult(result, projectName, project)

			} catch (Cancelled ignored) {
				log?.cancelledBuilding(analyzerName)
			} catch (Exception e) {
				ui.showAnalyzerError(analyzerName, Unscramble.unscrambleThrowable(e), project)
			}
		}
	}

	@SuppressWarnings("GrMethodMayBeStatic")
	def fileCountByFileExtension(Project project) {
		def scope = GlobalSearchScope.projectScope(project)
		FileTypeManager.instance.registeredFileTypes.inject([:]) { LinkedHashMap map, FileType fileType ->
			int fileCount = FileBasedIndex.instance.getContainingFiles(FileTypeIndex.NAME, fileType, scope).size()
			if (fileCount > 0) map.put(fileType.defaultExtension, fileCount)
			map
		}.sort{ -it.value }
	}

	def onProjectOpened(Project project) {
		def grabberConfig = historyStorage.loadGrabberConfigFor(project.name)
		if (grabberConfig.grabOnVcsUpdate)
			vcsAccess.addVcsUpdateListenerFor(project.name, this.&grabHistoryOnVcsUpdate)
	}

	def onProjectClosed(Project project) {
		vcsAccess.removeVcsUpdateListenerFor(project.name)
	}

	def grabHistoryOf(Project project) {
		if (grabHistoryIsInProgress) return ui.showGrabbingInProgressMessage(project)
		if (vcsAccess.noVCSRootsIn(project)) return ui.showNoVcsRootsMessage(project)

		def saveConfig = { HistoryGrabberConfig userInput ->
			historyStorage.saveGrabberConfigFor(project.name, userInput)
		}

		def grabberConfig = historyStorage.loadGrabberConfigFor(project.name)
		ui.showGrabbingDialog(grabberConfig, project, saveConfig) { HistoryGrabberConfig userInput ->
			saveConfig(userInput)

			if (userInput.grabOnVcsUpdate)
				vcsAccess.addVcsUpdateListenerFor(project.name, this.&grabHistoryOnVcsUpdate)
			else
				vcsAccess.removeVcsUpdateListenerFor(project.name)

			grabHistoryIsInProgress = true
			ui.runInBackground("Grabbing project history") { ProgressIndicator indicator ->
				try {
                    def message = doGrabHistory(
	                        project,
	                        userInput.outputFilePath,
	                        userInput.from, userInput.to,
	                        userInput.grabChangeSizeInLines,
	                        indicator
                    )
					ui.showGrabbingFinishedMessage(message, project)
				} finally {
					grabHistoryIsInProgress = false
				}
			}
		}
	}

	def grabHistoryOnVcsUpdate(Project project, Time now = Time.now()) {
		if (grabHistoryIsInProgress) return
		def config = historyStorage.loadGrabberConfigFor(project.name)
		now = now.withTimeZone(config.lastGrabTime.timeZone())
		if (config.lastGrabTime.floorToDay() == now.floorToDay()) return

		grabHistoryIsInProgress = true
		ui.runInBackground("Grabbing project history") { ProgressIndicator indicator ->
			try {
				def toDate = now.toDate().withTimeZone(config.lastGrabTime.timeZone())
				doGrabHistory(project, config.outputFilePath, null, toDate, config.grabChangeSizeInLines, indicator)

				historyStorage.saveGrabberConfigFor(project.name, config.withLastGrabTime(now))
			} finally {
				grabHistoryIsInProgress = false
			}
		}
	}

	private doGrabHistory(Project project, String outputFile, Date from, Date to,
	                      boolean grabChangeSizeInLines, indicator) {
		def storageReader = historyStorage.eventStorageReader(outputFile)
		def storedDateRange = dateRangeBetween(storageReader.firstEvent(), storageReader.lastEvent())

		if (from == null) from = storedDateRange.to
		def requestDateRange = new DateRange(from, to)
		def dateRanges = requestDateRange.subtract(storedDateRange)
		def cancelled = new Cancelled() {
			@Override boolean isTrue() {
				indicator?.canceled
			}
		}
		log?.loadingProjectHistory(dateRanges.first().from, dateRanges.last().to)

		def hadErrors = false
		def storageWriter = historyStorage.eventStorageWriter(outputFile)
		try {
			def minedCommits = vcsAccess.readMinedCommits(dateRanges, project, grabChangeSizeInLines, indicator, cancelled)
			for (MinedCommit minedCommit in minedCommits) {
				storageWriter.addData(minedCommit.dataList)
			}
		} finally {
			storageWriter.flush()
		}

		def messageText = ""
		def outputFileLink = "<a href='file:///${new File(outputFile).canonicalPath}'>${new File(outputFile).name}</a>"
		def allVisualizationsLink = "<a href='file:///${new File(outputFile).canonicalPath}/visualize'>all vizualizations</a>"
		if (storageReader.hasNoEvents()) {
			messageText += "Grabbed history to ${outputFileLink}<br/>"
			messageText += "However, it has nothing in it probably because there are no commits ${formatRange(requestDateRange)}."
		} else {
			def newStoredDateRange = dateRangeBetween(storageReader.firstEvent(), storageReader.lastEvent())
			messageText += "<br/>Grabbed history to ${outputFileLink}.<br/>"
			messageText += "It contains history ${formatRange(newStoredDateRange)}.<br/><br/>"
			messageText += "You can run ${allVisualizationsLink} or choose one in plugin popup menu."
		}
		if (hadErrors) {
			messageText += "<br/>There were errors while reading commits from VCS, please check IDE log for details."
		}
		messageText
	}

	private static String formatRange(DateRange dateRange) {
		def from = dd_MM_yyyy.format(dateRange.from)
		def to = dd_MM_yyyy.format(dateRange.to)
		"from <b>${from}</b> to <b>${to}</b>"
	}

	def showCurrentFileHistoryStats(Project project) {
		def virtualFile = PluginUtil.currentFileIn(project)
		if (virtualFile == null) return

		def filePath = new LocalFilePath(virtualFile.canonicalPath, false)
		def vcsManager = project.getComponent(ProjectLevelVcsManager)

		ui.runInBackground("Looking up history for ${virtualFile.name}") { ProgressIndicator indicator ->
			def commits = []
			def allVcs = vcsManager.allVcsRoots*.vcs.unique()

			// could use this vcs.committedChangesProvider.getOneList(virtualFile, revisionNumber)
			// to get actual commits and find files in the same commit, but it's too slow and freezes UI for some reason
			for (vcs in allVcs) {
				if (!vcs?.vcsHistoryProvider?.canShowHistoryFor(virtualFile)) continue
				def session = vcs?.vcsHistoryProvider?.createSessionFor(filePath)
				if (session == null) continue
				commits.addAll(session.revisionList)
				if (indicator.canceled) return
			}
			indicator.fraction += 0.5

			if (!commits.empty) {
				def summary = createSummaryStatsFor(commits, virtualFile)
				ui.showFileHistoryStatsToolWindow(project, summary)
			} else {
				ui.showFileHasNoVcsHistory(virtualFile)
			}
		}
	}

	private static Map createSummaryStatsFor(Collection<VcsFileRevision> commits, VirtualFile virtualFile) {
		def creationDate = new Date(commits.min{it.revisionDate}.revisionDate)
		def fileAgeInDays = use(TimeCategory) {
			(Date.today().javaDate() - commits.min{it.revisionDate}.revisionDate).days
		}

		def commitsAmountByAuthor = commits
				.groupBy{ it.author.trim() }
				.collectEntries{[it.key, it.value.size()]}
				.sort{-it.value}

		def commitsAmountByPrefix = commits.groupBy{ prefixOf(it.commitMessage) }.collectEntries{[it.key, it.value.size()]}.sort{-it.value}

		[
				virtualFile: virtualFile,
				amountOfCommits: commits.size(),
				creationDate: creationDate,
				fileAgeInDays: fileAgeInDays,
				commitsAmountByAuthor: commitsAmountByAuthor.take(10),
				commitsAmountByPrefix: commitsAmountByPrefix.take(10)
		]
	}

	private static prefixOf(String commitMessage) {
		def words = commitMessage.split(" ")
		words.size() > 0 ? words[0].trim() : ""
	}

	def openScriptEditorFor(Project project, File historyFile) {
		def fileName = FileUtil.getNameWithoutExtension(historyFile.name) + ".groovy"
		def scriptFile = scriptStorage.findOrCreateScriptFile(fileName)
		ui.openFileInIdeEditor(scriptFile, project)
	}

	def runCurrentFileAsScript(Project project) {
		saveAllIdeFiles()
		def virtualFile = PluginUtil.currentFileIn(project)
		if (virtualFile == null) return
		def scriptFilePath = virtualFile.canonicalPath
		def scriptFileName = virtualFile.name

		ui.runInBackground("Running script: $scriptFileName") { ProgressIndicator indicator ->
			def loaderListener = new GroovyScript.Listener() {
				@Override void loadingError(String message) { ui.showScriptError(scriptFileName, message, project) }
				@Override void loadingError(Throwable e) { ui.showScriptError(scriptFileName, Unscramble.unscrambleThrowable(e), project) }
				@Override void runningError(Throwable e) { ui.showScriptError(scriptFileName, Unscramble.unscrambleThrowable(e), project) }
			}
			def analyzersLoader = new AnalyzerScriptLoader(scriptFilePath, loaderListener)
			def analyzers = analyzersLoader.load()
			if (analyzers == null) return ui.failedToLoadAnalyzers(scriptFilePath)

			def historyFileName = FileUtil.getNameWithoutExtension(scriptFileName) + ".csv"
			def hasHistory = historyStorage.historyExistsFor(historyFileName)
			if (!hasHistory) return ui.showNoHistoryForScript(scriptFileName)

			invokeOnEDT {
				def combinedAnalyzer = new CombinedAnalyzer(analyzers)
				runAnalyzer(new File(historyFileName), project, combinedAnalyzer, scriptFileName)
			}
		}
	}

	boolean isCurrentFileScript(Project project) {
		def virtualFile = PluginUtil.currentFileIn(project)
		scriptStorage.isScriptFile(virtualFile.canonicalPath)
	}

	private static void saveAllIdeFiles() {
		ApplicationManager.application.runWriteAction(new Runnable() {
			void run() { FileDocumentManager.instance.saveAllDocuments() }
		})
	}
}

interface CodeHistoryMinerPluginLog {
	def loadingProjectHistory(Date fromDate, Date toDate)

	def cancelledBuilding(String visualizationName)

	def measuredDuration(def entry)
}
