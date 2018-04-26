package loader

import com.google.gson.Gson
import org.apache.commons.io.FileUtils
import org.apache.commons.io.filefilter.TrueFileFilter
import java.io.File
import java.io.FileReader
import kotlin.collections.set

fun getDirPaths(dir: File): List<String> {
    val iter = FileUtils.iterateFiles(dir, TrueFileFilter.INSTANCE, TrueFileFilter.INSTANCE)
    return iter.asSequence()
            .filter { it.isFile }
            .map { it.absolutePath }
            .filter { !it.startsWith(".git/") && !it.startsWith(".idea/") }
            .toList()
}

//<Project name, <changes data, reviews data>>
fun getAllFiles(resultsFolderName: String): Map<String, Pair<String, String>> {
    val resultsDir = File(resultsFolderName)

    val changeFilesPerProject: MutableMap<String, String> = HashMap()
    val reviewFilesPerProject: MutableMap<String, String> = HashMap()

    val allFiles = getDirPaths(resultsDir).filter { it.endsWith("/out.json") }
    val reviewFiles = allFiles.filter { it.contains("/reviews/") }
    val changeFiles = allFiles.filter { it.contains("/changes/") }

    reviewFiles.forEach {
        val name = it.substringBeforeLast("/reviews/").substringAfterLast("/results-process/")
        reviewFilesPerProject[name] = it
    }

    changeFiles.forEach {
        val name = it.substringBeforeLast("/changes/").substringAfterLast("/results-process/")
        changeFilesPerProject[name] = it
    }

    return reviewFilesPerProject.map { Pair(it.key, Pair(changeFilesPerProject[it.key] ?: "null", it.value)) }.toMap()
}

fun getRevRecCSV(resultsFolderName: String): List<String> {
    val lines: MutableList<String> = mutableListOf(REVREC_CSV_HEADER)
    val MIN_REVIEWS = 0
    getAllFiles(resultsFolderName).forEach { name, files ->
        val changesFile = files.first
        val reviewsFile = files.second

        val gson = Gson()
        val projectResults = gson.fromJson(FileReader(reviewsFile), VerboseProjectResult::class.java)

        val totalReviews = projectResults.reviewInfos.size

        if (totalReviews > MIN_REVIEWS) {
            lines.add(getProjectObservation(projectResults))
        }
    }
    return lines

//    println("ALL PROJECTS (with > $MIN_REVIEWS reviews):")
//    getProjectRevRecResultsObservation(allReviewProcessingResults)
}

data class TopKAccuracy(val top1: Double, val top2: Double, val top3: Double, val top5: Double, val top10: Double)

data class ReviewProcessingResult(val reviewId: String, val linearChanges: Int, val fullChanges: Int,
                                  val linearRanking: List<String>, val fullRanking: List<String>,
                                  val linearMRR: Double, val fullMRR: Double, val MRRDiff: Double,
                                  val linearTopKAccuracy: TopKAccuracy, val fullTopKAccuracy: TopKAccuracy,
                                  val rankCorrelation: Double)

fun getReviewerRanks(ranking: List<String>, reviewers: List<String?>): List<Int> {
    fun isValid(user: String?): Boolean {
        if (user == null) return false
        return user.isNotBlank()
    }

    fun getRank(reviewer: String): Int {
        val index = ranking.filter { isValid(it) }.indexOf(reviewer)
        return if (index == -1) 0 else index + 1
    }

//    println("Reviewers: " + reviewers.filter { isValid(it) })
//    println("Ranked users: " + ranking.filter { isValid(it) })

    return reviewers.filter { isValid(it) }.map { getRank(it!!) }
}

fun getTopKAccuracy(reviewerRanks: List<Int>): TopKAccuracy {
    return TopKAccuracy(
            top1 = calculateTopKAccuracy(1, reviewerRanks),
            top2 = calculateTopKAccuracy(2, reviewerRanks),
            top3 = calculateTopKAccuracy(3, reviewerRanks),
            top5 = calculateTopKAccuracy(5, reviewerRanks),
            top10 = calculateTopKAccuracy(10, reviewerRanks)
    )
}

fun calculateTopKAccuracy(k: Int, reviewerRanks: List<Int>): Double {
    return reviewerRanks.count { it in 1..k } * 1.0 / reviewerRanks.size
}

fun getMRR(ranks: List<Int>): Double {
    if (ranks.isEmpty()) return 0.0
    val reciprocalRanks = ranks.map { if (it == 0) 0.0 else 1.0 / it }
    return reciprocalRanks.average()
}

fun getScoresPerReviewer(reviewers: List<String>, scores: Map<String, Int>): Map<String, Int> {
    return reviewers.map { Pair(it, scores[it] ?: 0) }.toMap()
}

fun getSpearmanCorrelation(linearScores: Map<String, Int>, fullScores: Map<String, Int>): Double {

    val linearRanked = linearScores.toList().sortedByDescending { it.second }.map { it.first }.toMutableList()

    val fullRanked = fullScores.toList().sortedByDescending { it.second }.map { it.first }
    fullRanked.forEach {
        if (it !in linearRanked) linearRanked.add(it)
    }

    if (fullRanked.size == 1) return 1.0

    var rankDeltaSquareSum = 0.0

    linearRanked.forEachIndexed { index, name ->
        val linearRank = index + 1
        val fullRank = fullRanked.indexOf(name) + 1

        val rankDeltaSquared = Math.pow(1.0 * Math.abs(linearRank - fullRank), 2.0)

        rankDeltaSquareSum += rankDeltaSquared
    }
    val n = 1.0 * linearRanked.size

    val denominator = n * n * n - n

    val result = 1.0 - (6 * rankDeltaSquareSum / denominator)

    return result
}

fun getProjectObservation(data: VerboseProjectResult): String {
    println("***\nProject: ${data.name}")
    val results: MutableList<ReviewProcessingResult> = ArrayList()
    data.reviewInfos.forEach {
        if (it.reviewers.filterNotNull().isEmpty()) return@forEach
        val linearChangesCount = it.linearChanges.map { it.value.size }.sum()
        val fullChangesCount = it.fullChanges.map { it.value.size }.sum()

        val linearSortedAuthors = it.linearChanges.entries.toList().sortedByDescending { it.value.size }.map { it.key }
        val fullSortedAuthors = it.fullChanges.entries.toList().sortedByDescending { it.value.size }.map { it.key }

        val linearRanks = getReviewerRanks(linearSortedAuthors, it.reviewers)
        val fullRanks = getReviewerRanks(fullSortedAuthors, it.reviewers)

        val linearMRR = getMRR(linearRanks)
        val fullMRR = getMRR(fullRanks)

        val linearChangesPerAuthor = it.linearChanges.mapValues { it.value.size }
        val fullChangesPerAuthor = it.fullChanges.mapValues { it.value.size }

        val linearReviewerScores = getScoresPerReviewer(it.reviewers.filterNotNull(), linearChangesPerAuthor)
        val fullReviewerScores = getScoresPerReviewer(it.reviewers.filterNotNull(), fullChangesPerAuthor)

        val spearmanCorrelation = getSpearmanCorrelation(linearReviewerScores, fullReviewerScores)

        val reviewProcessingResult = ReviewProcessingResult(it.id, linearChangesCount, fullChangesCount,
                linearSortedAuthors, fullSortedAuthors, linearMRR, fullMRR, (fullMRR - linearMRR),
                getTopKAccuracy(linearRanks), getTopKAccuracy(fullRanks), spearmanCorrelation)

        results.add(reviewProcessingResult)
    }

    return getProjectRevRecResultsObservation(data.name, results)
}



fun getProjectRevRecResultsObservation(name: String, results: List<ReviewProcessingResult>): String {
    val reviewsWithDifference = results.filter { it.fullChanges > it.linearChanges }

    println("Total reviews: ${results.size}")
    val avgLinearMRR = results.map { it.linearMRR }.average()
    val avgFullMRR = results.map { it.fullMRR }.average()

    val linearTop1 = results.map { it.linearTopKAccuracy.top1 }.average()
    val fullTop1 = results.map { it.fullTopKAccuracy.top1 }.average()

    val linearTop2 = results.map { it.linearTopKAccuracy.top2 }.average()
    val fullTop2 = results.map { it.fullTopKAccuracy.top2 }.average()

    val linearTop3 = results.map { it.linearTopKAccuracy.top3 }.average()
    val fullTop3 = results.map { it.fullTopKAccuracy.top3 }.average()

    val linearTop5 = results.map { it.linearTopKAccuracy.top5 }.average()
    val fullTop5 = results.map { it.fullTopKAccuracy.top5 }.average()

    val linearTop10 = results.map { it.linearTopKAccuracy.top10 }.average()
    val fullTop10 = results.map { it.fullTopKAccuracy.top10 }.average()

    val rankCor = results.map { it.rankCorrelation }.average()


    println("\nReviews with difference: ${reviewsWithDifference.size}")
    val diffLinearMRR = reviewsWithDifference.map { it.linearMRR }.average()
    val diffFullMRR = reviewsWithDifference.map { it.fullMRR }.average()


    val difflinearTop1 = reviewsWithDifference.map { it.linearTopKAccuracy.top1 }.average()
    val difffullTop1 = reviewsWithDifference.map { it.fullTopKAccuracy.top1 }.average()

    val difflinearTop2 = reviewsWithDifference.map { it.linearTopKAccuracy.top2 }.average()
    val difffullTop2 = reviewsWithDifference.map { it.fullTopKAccuracy.top2 }.average()

    val difflinearTop3 = reviewsWithDifference.map { it.linearTopKAccuracy.top3 }.average()
    val difffullTop3 = reviewsWithDifference.map { it.fullTopKAccuracy.top3 }.average()

    val difflinearTop5 = reviewsWithDifference.map { it.linearTopKAccuracy.top5 }.average()
    val difffullTop5 = reviewsWithDifference.map { it.fullTopKAccuracy.top5 }.average()

    val difflinearTop10 = reviewsWithDifference.map { it.linearTopKAccuracy.top10 }.average()
    val difffullTop10 = reviewsWithDifference.map { it.fullTopKAccuracy.top10 }.average()

    val cprLinear = results.map { it.linearChanges }.average()
    val cprFull = results.map { it.fullChanges }.average()

    val diffCprLinear = reviewsWithDifference.map { it.linearChanges }.average()
    val diffCprFull = reviewsWithDifference.map { it.fullChanges }.average()

    return "$name,${results.size},${reviewsWithDifference.size},$avgLinearMRR,$avgFullMRR,$linearTop1,$fullTop1,$linearTop2,$fullTop2,$linearTop3,$fullTop3,$linearTop5,$fullTop5,$linearTop10,$fullTop10,$cprLinear,$cprFull," +
            "$diffLinearMRR,$diffFullMRR,$difflinearTop1,$difffullTop1,$difflinearTop2,$difffullTop2,$difflinearTop3,$difffullTop3,$difflinearTop5,$difffullTop5,$difflinearTop10,$difffullTop10,$diffCprLinear,$diffCprFull"
}

val REVREC_CSV_HEADER = "name,nReviews,nDiffReviews,mrrL,mrrF,top1L,top1F,top2L,top2F,top3L,top3F,top5L,top5F,top10L,top10F,cprL,cprF," +
        "diff_mrrL,diff_mrrF,diff_top1L,diff_top1F,diff_top2L,diff_top2F,diff_top3L,diff_top3F,diff_top5L,diff_top5F,diff_top10L,diff_top10F,diff_cprL,diff_cprF"