package loader

import org.researchgroup.git2neo.model.Action

data class VerboseReviewInfo(val id: String, val reviewers: List<String?>,
                             val linearChanges: Map<String, Collection<Int>>,
                             val fullChanges: Map<String, Collection<Int>>)

data class FileChangeInfo(val path: String, val oldPath: String?, val action: Action)

data class VerboseChangeInfo(val id: Int, val sha: String, val author: String, val committer: String,
                             val authorTime: Long, val committerTime: Long, val fileChangeInfos: Collection<FileChangeInfo>)

data class VerboseProjectResult(val name: String, val changeInfos: Collection<VerboseChangeInfo>,
                                val reviewInfos: Collection<VerboseReviewInfo>)
