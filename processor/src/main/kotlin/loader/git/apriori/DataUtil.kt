package loader.git.apriori

import de.mrapp.apriori.Item
import de.mrapp.apriori.Transaction
import org.researchgroup.git2neo.model.Commit
import org.researchgroup.git2neo.model.FileRevision


data class ChangedFile(val path: String): Item {
    override fun compareTo(other: Item): Int {
        return toString().compareTo(other.toString())
    }
}

data class CommitTransaction(val sha: String, val files: List<ChangedFile>): Transaction<ChangedFile> {
    override fun iterator(): MutableIterator<ChangedFile> {
        return files.toMutableList().listIterator()
    }
}

data class CommitsList(val commits: List<CommitTransaction>): Iterable<CommitTransaction> {
    override fun iterator(): Iterator<CommitTransaction> {
        return commits.listIterator()
    }
}

fun FileRevision.toChangedFile(): ChangedFile {
    return ChangedFile(path)
}

fun Commit.toCommitTransaction(): CommitTransaction {
    return CommitTransaction(info.id.idString, changes.map { it.toChangedFile() })
}

data class ProjectSummaryEntry(val project: String, val algorithmType: AlgorithmType, val historyType: HistoryType, val commits: Int, val paths: Int,
                               val avgFilesInCommit: Double, val avgRules: Double, val avgHistorySize: Double,
                               val avgPredictionsCount: Double, val successCount: Int, val failureCount: Int, val noPredictionCount: Int) {
    fun toCsvLine(): String = "$project,$algorithmType,$historyType,$commits,$paths,$avgFilesInCommit,$avgRules,$avgHistorySize,$avgPredictionsCount,$successCount,$failureCount,$noPredictionCount"
}

val CP_ENTRY_CSV_HEADER = "project,algorithmType,historyType,nCommits,nPaths,avgFilesInCommit,avgRules,avgHistorySize,avgPredictionsCount,successCount,failureCount,noPredictionCount"


data class ChangePredictionProjectSummary(val name: String, val entries: Collection<ProjectSummaryEntry>)

fun getSummaryEntry(project: String, algorithmType: AlgorithmType, historyType: HistoryType, evaluationResults: Collection<FileEvaluationResult>): ProjectSummaryEntry {
    return ProjectSummaryEntry(
            project,
            algorithmType,
            historyType,
            evaluationResults.map { it.sha }.toSet().size,
            evaluationResults.map { it.path }.toSet().size,
            evaluationResults.map { it.filesInCommit }.average(),
            evaluationResults.map { it.ruleCount }.average(),
            evaluationResults.map { it.historySize }.average(),
            evaluationResults.map { it.predictionsCount }.average(),
            evaluationResults.count { it.verdict == EvaluationOutcome.SUCCESS },
            evaluationResults.count { it.verdict == EvaluationOutcome.FAILURE },
            evaluationResults.count { it.verdict == EvaluationOutcome.NO_PREDICTION }
    )
}

fun getProjectSummary(name: String, evaluationResults: Collection<FileEvaluationResult>): ChangePredictionProjectSummary {
    return ChangePredictionProjectSummary(name, listOf(
            getSummaryEntry(name, AlgorithmType.SINGLE_FILE, HistoryType.FULL, evaluationResults.filter { it.algorithmType == AlgorithmType.SINGLE_FILE && it.historyType == HistoryType.FULL }),
            getSummaryEntry(name, AlgorithmType.SINGLE_FILE, HistoryType.LINEAR, evaluationResults.filter { it.algorithmType == AlgorithmType.SINGLE_FILE && it.historyType == HistoryType.LINEAR }),
            getSummaryEntry(name, AlgorithmType.OTHER_FILES, HistoryType.FULL, evaluationResults.filter { it.algorithmType == AlgorithmType.OTHER_FILES && it.historyType == HistoryType.FULL }),
            getSummaryEntry(name, AlgorithmType.OTHER_FILES, HistoryType.LINEAR, evaluationResults.filter { it.algorithmType == AlgorithmType.OTHER_FILES && it.historyType == HistoryType.LINEAR })
    ))
}