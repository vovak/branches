package org.researchgroup.git2neo.driver

import org.junit.After
import org.junit.Before
import org.neo4j.test.TestGraphDatabaseFactory
import org.researchgroup.git2neo.model.*
import org.researchgroup.git2neo.util.getFileRevisionId
import java.io.File

/**
 * @author
 * @since 21/11/16
 */
abstract class CommitIndexTestBase {
    lateinit var myIndex: CommitIndex

    @Before
    fun initIndex() {
        val path = "./testdb"
        val db = TestGraphDatabaseFactory().newImpermanentDatabase(File(path))
        myIndex = CommitIndex(db, javaClass.canonicalName)
    }

    @After
    fun cleanUp() {
        myIndex.dispose()
    }

    fun getIndex(): CommitIndex {
        return myIndex
    }

    fun createCommit(id: String, parents: List<String>): Commit {
        val commitInfo = CommitInfo(CommitId(id), Contributor(""), Contributor(""), 0, 0, parents.map(::CommitId))
        return Commit(commitInfo, emptyList())
    }

    fun createCommit(id: String, parent: String?): Commit {
        val parents = if (parent == null) emptyList<String>() else listOf(parent)
        return createCommit(id, parents)
    }

    fun createCommit(id: String, parentIds: List<String>, changes: List<Triple<Action, String, String?>>): Commit {
        val commitInfo = CommitInfo(CommitId(id), Contributor(""), Contributor(""), 0, 0, parentIds.map(::CommitId))
        return Commit(commitInfo, changes.map {
            FileRevision(
                    getFileRevisionId(id, it.second),
                    it.second,
                    it.third,
                    commitInfo,
                    it.first,
                    null)
        })
    }

    fun createCommit(id: String, parentId: String?, changes: List<Triple<Action, String, String?>>): Commit {
        return createCommit(id, (if (parentId == null) emptyList<String>() else listOf(parentId)), changes)
    }
}