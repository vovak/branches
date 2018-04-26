package org.researchgroup.git2neo.driver

import org.junit.Assert
import org.junit.Test
import org.neo4j.graphdb.Direction
import org.researchgroup.git2neo.model.Action
import org.researchgroup.git2neo.model.Commit
import org.researchgroup.git2neo.model.CommitId
import java.util.*

/**
 * Created by on 5/2/17.
 */
class ChangeConnectionStructureTest : CommitIndexTestBase() {

    fun findFileChangeNodeParentConnections(index: CommitIndex, commitId: String, path: String): Collection<Long> {
        var result: Collection<Long> = emptyList()
        index.withDb {
            val commitNode = index.getRawNode(CommitId(commitId))!!
            val changeNode = commitNode.getRelationships(CONTAINS).first { it.endNode.getProperty("path") as String == path }.endNode
            result = changeNode.getRelationships(PARENT, Direction.OUTGOING).map { it.endNode.id }
        }
        return result
    }

    @Test
    fun testParentConnectionCounts1() {
        val index = getIndex()
        val commits: MutableList<Commit> = ArrayList()

        commits.add(createCommit("1", null, listOf(Triple(Action.CREATED, "a", null))))
        commits.add(createCommit("2", "1", listOf(Triple(Action.MODIFIED, "a", null))))
        commits.add(createCommit("3", "2", listOf(Triple(Action.MODIFIED, "a", null))))

        index.addAll(commits)

        val parentVersions = findFileChangeNodeParentConnections(index, "3", "a")

        Assert.assertEquals("Change node should be connected to one nearest parent in linear case",
                1,
                parentVersions.size)
    }

    @Test
    fun testParentConnectionCounts2() {
        val index = getIndex()
        val commits: MutableList<Commit> = ArrayList()

        commits.add(createCommit("1", null, listOf(Triple(Action.CREATED, "a", null), Triple(Action.CREATED, "b", null))))
        commits.add(createCommit("2", "1", listOf(Triple(Action.MODIFIED, "a", null))))
        commits.add(createCommit("3", "2", listOf(Triple(Action.MODIFIED, "a", null), Triple(Action.MODIFIED, "b", null))))
        commits.add(createCommit("4", "2", listOf(Triple(Action.MODIFIED, "a", null), Triple(Action.MODIFIED, "b", null))))
        commits.add(createCommit("5", "4", listOf(Triple(Action.MODIFIED, "a", null))))
        commits.add(createCommit("6", "3", listOf(Triple(Action.MODIFIED, "a", null))))
        commits.add(createCommit("7", listOf("5", "6"), listOf(Triple(Action.MODIFIED, "b", null))))
        commits.add(createCommit("8", "7", listOf(Triple(Action.MODIFIED, "a", null))))
        commits.add(createCommit("9", "8", listOf(Triple(Action.MODIFIED, "a", null), Triple(Action.MODIFIED, "b", null))))

        index.addAll(commits)

        Assert.assertEquals("Change node should only be connected to the nearest parents", 1, findFileChangeNodeParentConnections(index, "9", "a").size) // to change in node #8
        Assert.assertEquals("Change node should only be connected to the nearest parents", 1, findFileChangeNodeParentConnections(index, "9", "b").size) // to change in node #7
        Assert.assertEquals("Change node should only be connected to the nearest parents", 2, findFileChangeNodeParentConnections(index, "7", "b").size) // to changes in nodes #3 and #4
        Assert.assertEquals("Change node should only be connected to the nearest parents", 2, findFileChangeNodeParentConnections(index, "8", "a").size) // to changes in nodes #5 and #6
    }

}


