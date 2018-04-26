package org.researchgroup.git2neo.driver.loader

import org.apache.commons.io.FileUtils
import org.apache.commons.io.filefilter.TrueFileFilter
import org.neo4j.graphdb.GraphDatabaseService
import org.neo4j.graphdb.factory.GraphDatabaseFactory
import org.researchgroup.git2neo.driver.CommitIndexFactory
import org.researchgroup.git2neo.driver.loader.util.extractFolder
import org.researchgroup.git2neo.driver.loader.util.getRepoArchivePath
import org.researchgroup.git2neo.driver.loader.util.getRepoUnpackedPath
import org.researchgroup.git2neo.model.CommitId
import org.researchgroup.git2neo.model.FileRevision
import org.researchgroup.git2neo.model.History
import org.researchgroup.git2neo.util.getFileRevisionId
import java.io.File


data class ChangeObservation(val fileId: Int, val sha: String, val action: String,
                             val path: String, val oldPath: String,
                             val time: Long, val author: String)

fun ChangeObservation.toCsvString(): String {
    return "$fileId,$sha,$action,$path,$oldPath,$time,$author"
}

fun getAllPaths(dir: File): List<String> {
    val iter = FileUtils.iterateFiles(dir, TrueFileFilter.INSTANCE, TrueFileFilter.INSTANCE)
    return iter.asSequence()
            .filter { it.isFile }
            .map { it.toRelativeString(dir) }
            .filter { !it.startsWith(".git/") && !it.startsWith(".idea/") }
            .toList()
}

val OBSERVATION_HEADER = listOf("fileId", "sha", "action", "path", "oldPath", "time", "author").joinToString(",")

fun unzipRepo(name: String): File {
    extractFolder(getRepoArchivePath(name), getRepoUnpackedPath())
    return File(getRepoUnpackedPath() + "/$name")
}

fun loadDb(path: String): GraphDatabaseService {
    val graphDb = GraphDatabaseFactory()
            .newEmbeddedDatabaseBuilder(File(path))
            .newGraphDatabase()

    return graphDb
}

fun processUnzippedRepo(name: String, gitDir: File) {
    val repoDir = File(gitDir.absolutePath.substringBefore(".git"))
    val allPaths = getAllPaths(repoDir).toSet()

    println(allPaths)

    val db = loadDb(name)
    val commitIndex = CommitIndexFactory().loadCommitIndex(db, "db_$name")
    val repoInfo = GitLoader(commitIndex).loadGitRepo(gitDir.absolutePath, false)

    if (repoInfo.headSha == null) {
        println("Cannot process the repo: HEAD not found")
        return
    }
    val history = commitIndex.getCommitHistory(CommitId(repoInfo.headSha))
    println(history)

    val lastRevisionPerPath: MutableMap<String, String> = HashMap()
    val lastTimePerPath: MutableMap<String, Long> = HashMap()

    var processed = 0
    history.items.forEach { commit ->
        if(++processed % 100 == 0) {
            println("Processed $processed commits of ${repoInfo.commitsCount}")
        }
        commit.changes.forEach file@ { fileRevision ->
            if (fileRevision.path !in allPaths) return@file
            val time = lastTimePerPath.get(fileRevision.path)
            if (time == null || time < commit.info.committerTime) {
                lastRevisionPerPath[fileRevision.path] = commit.info.id.idString
                lastTimePerPath[fileRevision.path] = commit.info.committerTime
            }
        }
    }

    println("Files in history: " + history.items.map { it.changes.map { it.path }.toList() }.flatten().toSet().size)
    println("Files in repo: " + allPaths.size)
    println("Files in last commit map: " + lastTimePerPath.size)

    var same = 0
    var different = 0

    var linearHistories: MutableCollection<History<FileRevision>> = ArrayList()
    var fullHistories: MutableCollection<History<FileRevision>> = ArrayList()


    lastRevisionPerPath.forEach { path, lastRevision ->
        val fullHistory = commitIndex.getChangesHistory(getFileRevisionId(lastRevision, path))
        val linearHistory = commitIndex.getChangesHistory(getFileRevisionId(lastRevision, path), true)

        fullHistories.add(fullHistory)
        linearHistories.add(linearHistory)

        if (fullHistory.items.size != linearHistory.items.size) {
            different++
            println(path)
            println("full: " + fullHistory.items.size + "; linear: " + linearHistory.items.size)
        } else {
            same++
        }
    }
    println("Same length of histories: $same/${allPaths.size} files; different: $different files")

    dumpHistories("${name}_linear", linearHistories)
    dumpHistories("${name}_full", fullHistories)

    db.shutdown()
}

fun dumpHistories(prefix: String, histories: Collection<History<FileRevision>>) {
    val lines: MutableList<String> = ArrayList()
    val counter = SeqCounter()
    lines.add(OBSERVATION_HEADER)
    lines.addAll(histories.map { getChangeObservations(it, counter) }.flatten().map { it.toCsvString() })

    val filename = "${prefix.replace("/", "_")}_changes.csv"

    writeLinesToFile(lines, filename, null)
}

class SeqCounter {
    var n = 0
    fun incrementAndGet(): Int {
        return ++n
    }
}

fun getChangeObservations(history: History<FileRevision>, seqCounter: SeqCounter): Collection<ChangeObservation> {
    fun getObservation(revision: FileRevision, fileId: Int): ChangeObservation {
        return ChangeObservation(
                fileId,
                revision.commitInfo.id.idString,
                revision.action.toString(),
                revision.path,
                revision.oldPath ?: "NULL",
                revision.commitInfo.committerTime,
                revision.commitInfo.committer.email)
    }
    val id = seqCounter.incrementAndGet()
    return history.items.map { getObservation(it, id) }
}


fun writeLinesToFile(lines: List<String>, filename: String, dirname: String?) {
    if (dirname != null) {
        val dir = File(dirname)
        if (!dir.exists()) {
            dir.mkdirs()
        }
    }
    val file = File((dirname ?: "") + filename)
    file.printWriter().use { out ->
        lines.forEach { out.println(it) }
    }
}




