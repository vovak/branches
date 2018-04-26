package loader.git.apriori

import de.mrapp.apriori.Apriori
import de.mrapp.apriori.AssociationRule
import de.mrapp.apriori.Filter
import de.mrapp.apriori.RuleSet
import de.mrapp.apriori.metrics.Support
import loader.git.GitRepoProcessor
import org.researchgroup.git2neo.driver.CommitIndex
import org.researchgroup.git2neo.model.Action
import org.researchgroup.git2neo.model.Commit
import org.researchgroup.git2neo.model.CommitId

val MAX_CHANGES_IN_COMMIT = 10
val MIN_COMMITS_IN_HISTORY = 5
val COMMIT_WINDOW_SIZE = 100

val MAX_RULES = 10

enum class HistoryType { LINEAR, FULL }
enum class EvaluationOutcome { SUCCESS, FAILURE, NO_PREDICTION }
enum class AlgorithmType { SINGLE_FILE, OTHER_FILES }
data class FileEvaluationResult(val algorithmType: AlgorithmType, val historyType: HistoryType, val sha: String, val path: String, val filesInCommit: Int, val ruleCount: Int, val historySize: Int,
                                val predictionsCount: Int, val verdict: EvaluationOutcome)


fun getCommits(currentCommitSha: String, shas: Collection<String>, commitIndex: CommitIndex): List<Commit> {
    val allOtherCommitIds = shas.toMutableSet()
    allOtherCommitIds.remove(currentCommitSha)
    if (allOtherCommitIds.isEmpty()) return emptyList()
    return allOtherCommitIds
            .map { commitIndex.get(CommitId(it))!! }
            .sortedByDescending { it.info.authorTime }
            .filter { it.changes.size <= MAX_CHANGES_IN_COMMIT }

}

fun evaluateChangePrediction(commit: Commit, histories: GitRepoProcessor.FileHistoriesForCommit, commitIndex: CommitIndex): List<FileEvaluationResult> {
    val allPaths = commit.changes.map { it.path }
    if (allPaths.size < 2) return emptyList()
    val result: MutableList<FileEvaluationResult> = ArrayList()
    commit.changes.filter { it.action == Action.MODIFIED }.forEach {
        val path = it.path
        val singleFileLinearHistory = histories.histories[path]!!.second.items.map { it.commitInfo.id.idString }.toSet()
        val singleFileFullHistory = histories.histories[path]!!.first.items.map { it.commitInfo.id.idString }.toSet()

        val otherFilesLinearHistory = histories.histories.filter { it.key != path }.map { it.value.second.items.map { it.commitInfo.id.idString } }.flatten().toSet()
        val otherFilesFullHistory = histories.histories.filter { it.key != path }.map { it.value.first.items.map { it.commitInfo.id.idString } }.flatten().toSet()

        result.addAll(evaluateHistories(AlgorithmType.SINGLE_FILE, commit.info.id.idString, path, singleFileLinearHistory, singleFileFullHistory, commitIndex))
        result.addAll(evaluateHistories(AlgorithmType.OTHER_FILES, commit.info.id.idString, path, otherFilesLinearHistory, otherFilesFullHistory, commitIndex))
    }
    return result
}

fun evaluateHistories(algorithmType: AlgorithmType, sha: String, path: String, linearHistory: Collection<String>, fullHistory: Collection<String>, commitIndex: CommitIndex): List<FileEvaluationResult> {

    val linearCommitsRaw = getCommits(sha, linearHistory, commitIndex).map { it.toCommitTransaction() }
    val fullCommitsRaw = getCommits(sha, fullHistory, commitIndex).map { it.toCommitTransaction() }

    val smallerHistorySize = Math.min(linearCommitsRaw.size, fullCommitsRaw.size)
    val trimSize = Math.min(smallerHistorySize, COMMIT_WINDOW_SIZE)

    val linearCommits = linearCommitsRaw.take(trimSize)
    val fullCommits = fullCommitsRaw.take(trimSize)

    if (linearCommits.map { it.sha }.toSet().containsAll(fullCommits.map { it.sha })) {
        return emptyList()
    }

    if (linearCommits.size < MIN_COMMITS_IN_HISTORY || fullCommits.size < MIN_COMMITS_IN_HISTORY) {
        return emptyList()
    }

    val otherChangesInCommit = commitIndex.get(CommitId(sha))!!.changes.filter { it.action == Action.MODIFIED }.map { it.path }.filter { it != path }
    if (otherChangesInCommit.isEmpty()) {
        return emptyList()
    }

    val linearHistoryRulesRaw = getRules(linearCommits)
    if (linearHistoryRulesRaw.isEmpty()) return emptyList()

    val fullHistoryRulesRaw = getRules(fullCommits)

    if (linearHistoryRulesRaw.isEmpty() || fullHistoryRulesRaw.isEmpty()) {
        return emptyList()
    }

    val ruleTrimSize = Math.min(Math.min(linearHistoryRulesRaw.size, fullHistoryRulesRaw.size), MAX_RULES)

    val linearEvaluationResult = evaluateRules(algorithmType, HistoryType.LINEAR, sha, path, linearHistoryRulesRaw, ruleTrimSize, otherChangesInCommit, linearCommits.size)
    val fullEvaluationResult = evaluateRules(algorithmType, HistoryType.FULL, sha, path, fullHistoryRulesRaw, ruleTrimSize, otherChangesInCommit, fullCommits.size)

    return listOf(linearEvaluationResult, fullEvaluationResult)
}

fun evaluateRules(algorithmType: AlgorithmType, historyType: HistoryType,
                  sha: String, path: String,
                  ruleSet: RuleSet<ChangedFile>, ruleTrimSize: Int,
                  otherChangesInCommit: Collection<String>, historySize: Int): FileEvaluationResult {
    fun AssociationRule<ChangedFile>.getBodyItems(): Collection<String> {
        return body.toList().map { it.path }
    }

    val rules = ruleSet
            .sortedByDescending { Support().evaluate(it) }.take(ruleTrimSize)

    val predictions = rules
            .filter { otherChangesInCommit.containsAll((it as AssociationRule<ChangedFile>).getBodyItems()) }
            .map { it.head }.flatten().toSet().map { it.path }

    val verdict = when {
        predictions.isEmpty() -> EvaluationOutcome.NO_PREDICTION
        predictions.contains(path) -> EvaluationOutcome.SUCCESS
        else -> EvaluationOutcome.FAILURE
    }

    return FileEvaluationResult(algorithmType, historyType, sha, path, otherChangesInCommit.size + 1, rules.size, historySize, predictions.size, verdict)
}


fun getRules(transactions: Collection<CommitTransaction>): RuleSet<ChangedFile> {
    val minSupport = 0.1
    val apriori = Apriori.Builder<ChangedFile>(minSupport).generateRules(0.3).create()

    val output = apriori.execute(transactions)

    val ruleFilter = Filter.forAssociationRules().byHeadSize(1, 1)

    val ruleSet = output.ruleSet?.filter(ruleFilter)
    return ruleSet!!
}
