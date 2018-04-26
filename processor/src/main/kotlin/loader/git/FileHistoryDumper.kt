package loader.git

import org.researchgroup.git2neo.driver.loader.writeLinesToFile
import org.researchgroup.git2neo.model.FileRevision
import org.researchgroup.git2neo.model.History

data class FileHistoryEntry(
        val headCommitSha: String,
        val headPath: String,
        val changeId: String,
        val commitSha: String,
        val path: String,
        val oldPath: String?,
        val authorEmail: String,
        val time: Long,
        val action: String,
        val parentIds: Collection<String>
) {
    fun toCsvLine(): String {
        return "$headCommitSha,$headPath,$changeId,$commitSha,$path,$oldPath,$authorEmail,$time,$action," +
                if(parentIds.isEmpty()) "null" else parentIds.joinToString(" ")
    }
}

val FHE_CSV_HEADER = "headCommitSha,headPath,changeId,commitSha,path,oldPath,authorEmail,time,action,parentIds"

data class FileHistory(
        val headCommitSha: String,
        val headPath: String,
        val entries: List<FileHistoryEntry>
)

fun FileRevision.toFileHistoryEntry(headCommitSha: String, headPath: String): FileHistoryEntry {
    return FileHistoryEntry(
            headCommitSha,
            headPath,
            id.stringId(),
            commitInfo.id.stringId(),
            path,
            oldPath,
            commitInfo.author.email,
            commitInfo.authorTime,
            action.toString(),
            parentRevisions?.map { it.stringId() } ?: emptyList()
    )
}

fun History<FileRevision>.toFileHistory(headCommitSha: String, headPath: String): FileHistory {
    return FileHistory(headCommitSha, headPath, items.map { it.toFileHistoryEntry(headCommitSha, headPath) })
}

fun dumpHistoriesToFile(histories: Collection<FileHistory>, filename: String) {
    val entries = histories.map { it.entries }.flatten()
    val lines = mutableListOf(FHE_CSV_HEADER)
    lines.addAll(entries.map { it.toCsvLine() })

    writeLinesToFile(lines, filename, null)
}

