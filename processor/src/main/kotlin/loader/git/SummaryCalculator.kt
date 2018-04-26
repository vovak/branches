package loader.git

data class RepositorySummary(
        val name: String,
        val totalCommits: Int,
        val reachableCommits: Int,
        val mainBranchReachableCommits: Int,
        val mergeCommits: Int,
        val filesInTree: Int,
        val uniqueAuthorsInRepo: Int,

        //File history length metrics
        val avgFullHistoryLength: Double,
        val avgLinearHistoryLength: Double,
        val filesWithDifferentHistories: Int,
        val avgFullHistoryLengthForFilesWithDifferentHistories: Double,
        val avgLinearHistoryLengthForFilesWithDifferentHistories: Double,

        //Contributors count metrics
        val avgAuthorsPerFileFullHistory: Double,
        val avgAuthorsPerFileLinearHistory: Double,
        val avgAuthorsPerFileFullHistoryForFilesWithDifference: Double,
        val avgAuthorsPerFileLinearHistoryForFilesWithDifference: Double
) {
    fun toCsvLine(): String {
        return "$name,$totalCommits,$reachableCommits,$mainBranchReachableCommits,$mergeCommits,$filesInTree,$uniqueAuthorsInRepo," +
                "$avgFullHistoryLength,$avgLinearHistoryLength,$filesWithDifferentHistories," +
                "$avgFullHistoryLengthForFilesWithDifferentHistories,$avgLinearHistoryLengthForFilesWithDifferentHistories," +
                "$avgAuthorsPerFileFullHistory,$avgAuthorsPerFileLinearHistory,$avgAuthorsPerFileFullHistoryForFilesWithDifference,$avgAuthorsPerFileLinearHistoryForFilesWithDifference"

    }
}

val CSV_HEADER = "name,totalCommits,reachableCommits,mainBranchReachableCommits,mergeCommits,filesInTree,uniqueAuthorsInRepo," +
        "avgFullHistoryLength,avgLinearHistoryLength,filesWithDifferentHistories," +
        "avgFullHistoryLengthForFilesWithDifferentHistories,avgLinearHistoryLengthForFilesWithDifferentHistories," +
        "avgAuthorsPerFileFullHistory,avgAuthorsPerFileLinearHistory,avgAuthorsPerFileFullHistoryForFilesWithDifference,avgAuthorsPerFileLinearHistoryForFilesWithDifference"

fun FileHistorySummary.size() = 1.0 * affectingCommits.size
fun FileHistorySummary.uniqueAuthors() = 1.0 * affectingCommits.map { it.email }.toSet().size

fun getRepositorySummary(data: RepositoryData): RepositorySummary {
    val allFiles = data.fileInfos
    val avgFullHistoryLength = allFiles.map { it.fullHistory.size() }.average()
    val avgLinearHistoryLength = allFiles.map { it.linearHistory.size() }.average()
    val filesWithDifferentHistories = allFiles.filter { it.fullHistory.size() > it.linearHistory.size() }

    val avgFullHistoryLengthForFilesWithDifferentHistories =
            if (filesWithDifferentHistories.isEmpty()) 0.0
            else filesWithDifferentHistories.map { it.fullHistory.size() }.average()

    val avgLinearHistoryLengthForFilesWithDifferentHistories =
            if (filesWithDifferentHistories.isEmpty()) 0.0
            else filesWithDifferentHistories.map { it.linearHistory.size() }.average()

    val avgAuthorsPerFileFullHistory = allFiles.map { it.fullHistory.uniqueAuthors() }.average()
    val avgAuthorsPerFileLinearHistory = allFiles.map { it.linearHistory.uniqueAuthors() }.average()
    val avgAuthorsPerFileFullHistoryForFilesWithDifference = filesWithDifferentHistories.map { it.fullHistory.uniqueAuthors() }.average()
    val avgAuthorsPerFileLinearHistoryForFilesWithDifference = filesWithDifferentHistories.map { it.linearHistory.uniqueAuthors() }.average()
    
    val uniqueAuthorsInRepo = data.allCommitInfos.map { it.info.info.author.email }.toSet().size

    return RepositorySummary(
            data.name,
            data.totalCommits,
            data.totalReachableCommits,
            data.linearReachableCommits,
            data.mergeCommits,
            data.fileInfos.size,
            uniqueAuthorsInRepo,

            avgFullHistoryLength,
            avgLinearHistoryLength,
            filesWithDifferentHistories.size,
            avgFullHistoryLengthForFilesWithDifferentHistories,
            avgLinearHistoryLengthForFilesWithDifferentHistories,

            avgAuthorsPerFileFullHistory,
            avgAuthorsPerFileLinearHistory,
            avgAuthorsPerFileFullHistoryForFilesWithDifference,
            avgAuthorsPerFileLinearHistoryForFilesWithDifference

    )
}