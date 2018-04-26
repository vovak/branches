package loader.git

import loader.ShortCommitInfo
import loader.git.apriori.*
import loader.toShortCommitInfo
import org.neo4j.graphdb.GraphDatabaseService
import org.neo4j.graphdb.factory.GraphDatabaseFactory
import org.researchgroup.git2neo.driver.CommitIndex
import org.researchgroup.git2neo.driver.CommitIndexFactory
import org.researchgroup.git2neo.driver.loader.GitLoader
import org.researchgroup.git2neo.driver.loader.getAllPaths
import org.researchgroup.git2neo.model.*
import org.researchgroup.git2neo.util.getFileRevisionId
import org.slf4j.LoggerFactory
import java.io.File

data class RepositoryStatistics(val totalChanges: Int)

fun IDENTITY_URL_PRODUCER(name: String) = name
fun GITHUB_URL_PRODUCER(name: String) = "https://github.com/$name"
fun APACHE_URL_PRODUCER(name: String) = "https://git.apache.org/$name.git"

val MAX_COMMITS_FOR_DUMP = 10000

private val log = LoggerFactory.getLogger("RepoAnalyzer")

data class AffectingCommitInfo(val sha: String, val action: Action, val email: String)
data class FileHistorySummary(val affectingCommits: Collection<AffectingCommitInfo>)
data class FileHistoryInfo(val path: String,
                           val currentVersionSha: String,
                           val linearHistory: FileHistorySummary,
                           val fullHistory: FileHistorySummary)

data class CommitData(val info: ShortCommitInfo, val reachableFromHead: Boolean, val reachableLinearlyFromHead: Boolean)

data class RepositoryData(
        val name: String,
        val totalCommits: Int,
        val totalReachableCommits: Int,
        val linearReachableCommits: Int,
        val mergeCommits: Int,
        val allCommitInfos: Collection<CommitData>,
        val fileInfos: Collection<FileHistoryInfo>,
        val changePredictionSummary: ChangePredictionProjectSummary
)

class GitRepoProcessor {
    fun loadDb(dbPath: String): GraphDatabaseService {

        val graphDb = GraphDatabaseFactory()
                .newEmbeddedDatabaseBuilder(File(dbPath))
                .newGraphDatabase()

        return graphDb
    }

    fun History<FileRevision>.toSummary(): FileHistorySummary {
        val affectingCommits: MutableList<AffectingCommitInfo> = ArrayList()
        items.forEach {
            affectingCommits.add(AffectingCommitInfo(it.commitInfo.id.stringId(), it.action, it.commitInfo.author.email))
        }
        return FileHistorySummary(affectingCommits)
    }

    fun downloadRepo(name: String, urlProducer: (String) -> (String)): String? {
        val downloader = RepositoryDownloader(urlProducer)
        val gitFolder = downloader.ensureRepo(name, getRepoFolder(name))
        if (gitFolder == null) {
            log.error("Repository download failed")
        }
        log.info("Repository ensured in $gitFolder")
        return gitFolder?.absolutePath
    }

    fun loadRepoToDb(name: String): GitLoader.RepositoryInfo {
        val gitFolder = File(getRepoFolder(name))
        val repoDir = File(gitFolder.absolutePath.substringBefore(".git"))
        val allPaths = getAllPaths(repoDir).toSet()

        println("paths in repo $name: ${allPaths.size}")

        val db = loadDb(getDbFolder(name))
        val commitIndex = CommitIndexFactory().loadCommitIndex(db, "db_$name")

        val repoInfo = GitLoader(commitIndex).loadGitRepo(gitFolder.absolutePath + "/.git", true)

        commitIndex.dispose()
        return repoInfo
    }

    data class FileHistoriesForCommit(val sha: String, val paths: List<String>, val histories: Map<String, Pair<History<FileRevision>, History<FileRevision>>>)

    fun analyzeRepo(name: String, repoInfo: GitLoader.RepositoryInfo): RepositoryData? {
        assert(isDownloaded(name))
        assert(isDbLoaded(name))

        if(repoInfo.headSha == null) {
            log.error("HEAD not found in repo, skipping")
            return null
        }

        val gitFolder = File(getRepoFolder(name))
        val repoDir = File(gitFolder.absolutePath.substringBefore(".git"))
        val allPaths = getAllPaths(repoDir).toSet()

        val db = loadDb(getDbFolder(name))
        val commitIndex = CommitIndexFactory().loadCommitIndex(db, "db_$name")

        val firstParentCommitHistory = commitIndex.getCommitHistory(CommitId(repoInfo.headSha!!), true)
        val fullCommitHistory = commitIndex.getCommitHistory(CommitId(repoInfo.headSha!!), false)

        if(fullCommitHistory.items.size > MAX_COMMITS_FOR_DUMP) {
            log.info("Project $name is too large for change prediction: ${fullCommitHistory.items.size} commits, max $MAX_COMMITS_FOR_DUMP")
            return null
        }

        val commitsReachableByFirstParent: Set<String> = firstParentCommitHistory.items.map { it.info.id.idString }.toSet()
        val commitsReachableTotal: Set<String> = fullCommitHistory.items.map { it.info.id.idString }.toSet()

        val lastRevisionPerPath: MutableMap<String, String> = HashMap()
        val lastTimePerPath: MutableMap<String, Long> = HashMap()

        val mergeCommits: MutableCollection<String> = HashSet()

        var processed = 0

        fun getFileHistoriesForCommit(commit: Commit): FileHistoriesForCommit {
            val result: MutableMap<String, Pair<History<FileRevision>, History<FileRevision>>> = HashMap()
            commit.changes.forEach {
                val fullHistory = commitIndex.getChangesHistory(it.id, false)
                val linearHistory = commitIndex.getChangesHistory(it.id, true)
                result[it.path] = Pair(fullHistory, linearHistory)
            }
            return FileHistoriesForCommit(commit.info.id.idString, result.keys.toList(), result)
        }

        val changePredictionEvaluationResults: MutableList<FileEvaluationResult> = ArrayList()

        fullCommitHistory.items.forEach { commit ->
            if (++processed % 100 == 0) {
                println("Processed $processed commits of ${fullCommitHistory.items.size}")
            }
            if (commit.info.parents.size > 1) {
                mergeCommits.add(commit.info.id.idString)
            }
            commit.changes.forEach file@{ fileRevision ->
                if (fileRevision.path !in allPaths) return@file
                val time = lastTimePerPath.get(fileRevision.path)
                if (time == null || time < commit.info.committerTime) {
                    lastRevisionPerPath[fileRevision.path] = commit.info.id.idString
                    lastTimePerPath[fileRevision.path] = commit.info.committerTime
                }
            }

            if (commit.info.parents.size == 1 && commit.changes.size <= MAX_CHANGES_IN_COMMIT) {
                val fileHistoriesForCommit = getFileHistoriesForCommit(commit)
                val evaluationResultsForCommit = evaluateChangePrediction(commit, fileHistoriesForCommit, commitIndex)
                changePredictionEvaluationResults.addAll(evaluationResultsForCommit)
            }
        }

        val projectChangePredictionSummary = getProjectSummary(name, changePredictionEvaluationResults)

        log.info("Files in history: " + fullCommitHistory.items.map { it.changes.map { it.path }.toList() }.flatten().toSet().size)
        log.info("Files in repo: " + allPaths.size)
        log.info("Files in last commit map: " + lastTimePerPath.size)
        if (allPaths.size != lastTimePerPath.size) {
            val inRepoButNotInMap = allPaths.filter { it !in lastTimePerPath.keys }
            val inMapButNotInRepo = lastTimePerPath.keys.filter { it !in allPaths }
            log.info("In repo but not in commit map: $inRepoButNotInMap")
            log.info("In commit map but not in repo: $inMapButNotInRepo")
        }

        val fileInfos: MutableList<FileHistoryInfo> = ArrayList()

        log.info("Calculating file infos...")
        lastRevisionPerPath.forEach { path, lastRevision ->
            val fullHistory = commitIndex.getChangesHistory(getFileRevisionId(lastRevision, path))
            val linearHistory = commitIndex.getChangesHistory(getFileRevisionId(lastRevision, path), true)
            fileInfos.add(FileHistoryInfo(
                    path,
                    lastRevision,
                    linearHistory.toSummary(),
                    fullHistory.toSummary()
            ))

        }
        log.info("Done")

        if(fullCommitHistory.items.size <= MAX_COMMITS_FOR_DUMP) {
            log.info("Dumping file histories...")
            dumpChangesHistory(fullCommitHistory, commitIndex, name)
            log.info("Done")
        } else {
            log.info("Repository is too large for dumping histories: ${fullCommitHistory.items.size} commits (limit $MAX_COMMITS_FOR_DUMP)")
        }

        commitIndex.dispose()

        val commitInfos = repoInfo.allCommits.map {
            CommitData(
                    it.toShortCommitInfo(),
                    commitsReachableTotal.contains(it.info.id.stringId()),
                    commitsReachableByFirstParent.contains(it.info.id.stringId())
            )
        }

        return RepositoryData(
                name,
                commitInfos.size,
                commitsReachableTotal.size,
                commitsReachableByFirstParent.size,
                mergeCommits.size,
                commitInfos,
                fileInfos,
                projectChangePredictionSummary)
    }

    fun dumpChangesHistory(commitsHistory: History<Commit>, commitIndex: CommitIndex, projectName: String) {
        val linearHistories: MutableCollection<FileHistory> = ArrayList()
        val fullHistories: MutableCollection<FileHistory> = ArrayList()
        val startTime = System.currentTimeMillis()
        commitsHistory.items.forEach {
            val sha = it.info.id.stringId()
            it.changes.forEach {
                val path = it.path
                val linearHistory = commitIndex.getChangesHistory(it.id, true)
                val fullHistory = commitIndex.getChangesHistory(it.id, false)

                linearHistories.add(linearHistory.toFileHistory(sha, path))
                fullHistories.add(fullHistory.toFileHistory(sha, path))
            }
        }
        println("Done collecting histories (${commitsHistory.items.size} commits) in ${System.currentTimeMillis() - startTime} ms")
        dumpHistoriesToFile(linearHistories, getLinearFileHistoriesFilePath(projectName))
        dumpHistoriesToFile(fullHistories, getFullFileHistoriesFilePath(projectName))

        zipFiles(listOf(getLinearFileHistoriesFilePath(projectName), getFullFileHistoriesFilePath(projectName)),
                getFileHistoriesArchivePath(projectName))

        deleteFile(getFullFileHistoriesFilePath(projectName))
        deleteFile(getLinearFileHistoriesFilePath(projectName))
    }
}