package loader

import com.google.gson.GsonBuilder
import org.researchgroup.git2neo.driver.CommitIndex
import org.researchgroup.git2neo.driver.CommitIndexFactory
import org.researchgroup.git2neo.driver.loader.GitLoader
import org.researchgroup.git2neo.driver.loader.loadDb
import org.researchgroup.git2neo.model.*
import org.researchgroup.git2neo.util.getFileRevisionId
import loader.git.isDbLoaded
import loader.git.setDbLoaded
import loader.git.writeRepoInfo
import java.io.File
import java.io.FileWriter

data class RecommendationResult(val reviewId: String, val reviewers: Collection<String?>,
                                val totalChanges: Int, val changesPerAuthor: Map<String, Int>)

data class ProjectResult(val name: String, val reviewsCount: Int,
                         val reviewsWithDifferentHistories: Int,
                         val linearChangesPerReview: Double, val fullChangesPerReview: Double,
                         val linearChangeBasedMRR: Double,
                         val fullChangeBasedMRR: Double) {
    fun toCsvString(): String {
        return "$name,$reviewsCount,$reviewsWithDifferentHistories,$linearChangesPerReview,$fullChangesPerReview,$linearChangeBasedMRR,$fullChangeBasedMRR"
    }
}

data class ShortCommitInfo(val info: CommitInfo, val changes: Collection<ShortFileInfo>)
data class ShortFileInfo(val path: String, val oldPath: String?, val action: Action)

fun Commit.toShortCommitInfo(): ShortCommitInfo {
    return ShortCommitInfo(this.info, this.changes.map {
        ShortFileInfo(it.path, it.oldPath, it.action)
    } )
}

const val CSV_HEADER = "name,reviews_count,reviews_with_different_histories,linear_cpr,full_cpr,linear_mrr,full_mrr"

fun CommitData.toFileRevisionIds(): Collection<FileRevisionId> {
    return files.map { getFileRevisionId(sha, it.path) }
}

fun getFileHistoriesForGerritReview(commitIndex: CommitIndex, review: ReviewData, firstParentOnly: Boolean): Collection<History<FileRevision>> {
    return review.commitDatas.map { commitIndex.getChangesHistoriesForCommit(CommitId(it.sha), firstParentOnly) }
            .flatten()
}


fun getChangesPerAuthor(history: Collection<History<FileRevision>>,
                        changeInfosMap: Map<String, Pair<Int, CommitInfo>>): Map<String, Collection<Int>> {
    val allChanges = history.map { it.items }.flatten()
    val allCommits = allChanges.map { it.commitInfo }.toSet()
    return allCommits
            .groupBy { it.author }
            .mapKeys { it.key.email }
            .mapValues { it.value.map { changeInfosMap[it.id.stringId()]!!.first } }
}

fun dumpProjectCommits(name: String, commits: Collection<Commit>) {
    val fileDir = File("$RESULTS_FOLDER/${name}/changes/")
    fileDir.mkdirs()
    val outFile = File(fileDir.absolutePath+"/out.json")
    FileWriter(outFile).use {
        val gson = GsonBuilder().setPrettyPrinting().create()
        gson.toJson(commits.map { it.toShortCommitInfo() }, it)
    }
}

fun processRepoAndReviews(name: String, repoPath: String,
                          reviews: Collection<ReviewData>,
                          userInfos: Collection<UserInfo>): VerboseProjectResult {
    val emailsPerId = userInfos.map { Pair(it.id, it.email) }.toMap()
    val gitDir = File("$repoPath/.git")



    val db = loadDb(getDbDirPath(name))
    val commitIndex = CommitIndexFactory().loadCommitIndex(db, "db_$name")
    if (!isDbLoaded(name)) {
        val repoInfo = GitLoader(commitIndex).loadGitRepo(gitDir.absolutePath, true, false)
        writeRepoInfo(name, repoInfo)
        setDbLoaded(name)
        dumpProjectCommits(name, repoInfo.allCommits)

    }
    val reviewResults: MutableCollection<VerboseReviewInfo> = ArrayList()
    val allChangeInfos: MutableMap<String, Pair<Int, CommitInfo>> = HashMap()

    reviews.filter { it.project == name }.forEach {
        println("Review ${it.reviewId}")

        val linearHistories = getFileHistoriesForGerritReview(commitIndex, it, true)
        val fullHistories = getFileHistoriesForGerritReview(commitIndex, it, false)

        fullHistories.map { it.items.map { it.commitInfo } }.flatten().toSet().forEach {
            if (!allChangeInfos.containsKey(it.id.idString)) {
                allChangeInfos[it.id.idString] = Pair(allChangeInfos.size, it)
            }
        }

        val reviewers = it.reviewersAndVotes.keys.map { emailsPerId[it] }
        println("REVIEWERS: " + reviewers.toList())

        val linearChangesPerAuthor = getChangesPerAuthor(linearHistories, allChangeInfos)
        val fullChangesPerAuthor = getChangesPerAuthor(fullHistories, allChangeInfos)

        val reviewInfo = VerboseReviewInfo(it.reviewId, reviewers, linearChangesPerAuthor, fullChangesPerAuthor)
        reviewResults.add(reviewInfo)
    }
    commitIndex.dispose()

    val changeInfos = allChangeInfos.map { VerboseChangeInfo(it.value.first,
            it.key,
            it.value.second.author.email,
            it.value.second.committer.email,
            it.value.second.authorTime,
            it.value.second.committerTime, emptyList())
    }


    return VerboseProjectResult(name, changeInfos, reviewResults)
}

fun getRRs(result: RecommendationResult): List<Double> {
    val authors = result.changesPerAuthor.entries.sortedByDescending { it.value }.map { it.key }

    return result.reviewers.filterNotNull().map {
        if (it !in authors) 0.0 else 1.0 / (authors.indexOf(it) + 1)
    }
}

fun processRecResults(name: String, linear: Collection<RecommendationResult>,
                      full: Collection<RecommendationResult>): ProjectResult {
    val allReviews = linear.map { it.reviewId }

    val linearReviews = linear.map { Pair(it.reviewId, it) }.toMap()
    val fullReviews = full.map { Pair(it.reviewId, it) }.toMap()

    val reviewsCount = allReviews.size
    var reviewsWithDifferentHistories = 0

    val linearRRs: MutableList<Double> = ArrayList()
    val fullRRs: MutableList<Double> = ArrayList()

    allReviews.forEach {
        val linearResult = linearReviews[it]!!
        val fullResult = fullReviews[it]!!
        if (linearResult.totalChanges != fullResult.totalChanges) {
            reviewsWithDifferentHistories++
        }
        linearRRs.addAll(getRRs(linearResult))
        fullRRs.addAll(getRRs(fullResult))
    }

    val linearMeanChanges = linear.map { 1.0 * it.totalChanges }.average()
    val fullMeanChanges = full.map { 1.0 * it.totalChanges }.average()

    println("Mean changes per review for linear case: $linearMeanChanges, full: $fullMeanChanges")

    return ProjectResult(name, reviewsCount, reviewsWithDifferentHistories,
            linearMeanChanges, fullMeanChanges, linearRRs.average(), fullRRs.average())
}