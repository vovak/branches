package org.researchgroup.git2neo.model

import java.io.Serializable

/**
 * @author
* @since 17/11/16
*/

interface Id<T> : Serializable {
    fun stringId(): String
}

data class CommitId(val idString: String) : Id<Commit> {
    override fun stringId(): String {
        return idString
    }
}

data class FileRevisionId(val id: String) : Id<FileRevision> {
    override fun stringId(): String {
        return id
    }
}

data class Contributor(val email: String) : Serializable

data class CommitInfo(
        val id: CommitId,
        val author: Contributor,
        val committer: Contributor,
        val authorTime:    Long,
        val committerTime: Long,
        val parents: List<CommitId>
) : Serializable

enum class Action {CREATED, MODIFIED, DELETED, MOVED}

data class FileRevision(
        val id: FileRevisionId,
        val path: String,
        val oldPath: String?,
        val commitInfo: CommitInfo,
        val action: Action,
        val parentRevisions: Collection<FileRevisionId>?
)

data class Commit(val info: CommitInfo, val changes: Collection<FileRevision>)

data class History<T>(val items: List<T>)

interface CommitStorage {
    fun add(commit: Commit)
    fun addAll(commits: Collection<Commit>)
    fun get(id: CommitId): Commit?
}
