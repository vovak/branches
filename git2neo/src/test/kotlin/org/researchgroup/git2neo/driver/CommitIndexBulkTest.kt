package org.researchgroup.git2neo.driver

import org.junit.Assert
import org.junit.Test
import org.researchgroup.git2neo.model.*
import java.util.*

/**
 * @author
 * @since 21/11/16
 */
class CommitIndexBulkTest : CommitIndexTestBase() {
    @Test
    fun testManyCommits() {
        val height = 100
        val index = getIndex()
        val allCommits: MutableList<Commit> = ArrayList()

        for (i in 1..height) {
            allCommits.add(createCommit("left_$i", if (i == 1) emptyList() else listOf("left_${i - 1}", "right_${i - 1}")))
            allCommits.add(createCommit("right_$i", if (i == 1) null else "right_${i - 1}"))
        }
        var start = System.currentTimeMillis()
        index.addAll(allCommits)
        var executionTime = System.currentTimeMillis() - start
        println("Inserted ${2 * height} revisions in ${1.0 * executionTime / 1000} seconds")


        start = System.currentTimeMillis()

        val leftHistory = index.getCommitHistory(CommitId("left_$height"))
        executionTime = System.currentTimeMillis() - start
        println("Acquired history of ${2 * height} revisions in ${1.0 * executionTime / 1000} seconds")

        Assert.assertEquals(2 * height - 1, leftHistory.items.size)


        start = System.currentTimeMillis()

        val rightHistory = index.getCommitHistory(CommitId("right_${height}"))
        executionTime = System.currentTimeMillis() - start
        println("Acquired history of ${height} revisions in ${1.0 * executionTime / 1000} seconds")

        Assert.assertEquals(height, rightHistory.items.size)
    }

    @Test
    fun testManyCommitsWithChanges() {
        val height = 1000
        val index = getIndex()
        val allCommits: MutableList<Commit> = ArrayList()

        allCommits.add(createCommit("left_1", null, listOf(Triple(Action.CREATED, "file.txt", null))))
        allCommits.add(createCommit("right_1", null, listOf(Triple(Action.CREATED, "file.txt", null))))

        for (i in 2..height) {
            allCommits.add(createCommit("left_$i", listOf("left_${i - 1}", "right_${i - 1}"), listOf(Triple(Action.MODIFIED, "file.txt", null))))
            allCommits.add(createCommit("right_$i", "right_${i - 1}", listOf(Triple(Action.MODIFIED, "file.txt", null))))
        }
        var start = System.currentTimeMillis()
        index.addAll(allCommits)
        var executionTime = System.currentTimeMillis() - start
        println("Inserted ${2 * height} revisions with changes in ${1.0 * executionTime / 1000} seconds")

        fun getHistoryForOnlyChange(commitId: String): History<FileRevision> {
            val changeId = index.get(CommitId(commitId))!!.changes.first().id
            val history = index.getChangesHistory(changeId)
            return history
        }

        start = System.currentTimeMillis()
        val leftTopHistory = getHistoryForOnlyChange("left_$height")
        executionTime = System.currentTimeMillis() - start
        val leftHistorySize = leftTopHistory.items.size
        Assert.assertEquals(2 * height - 1, leftHistorySize)
        println("Retrieved changes history of $leftHistorySize entries in ${1.0 * executionTime / 1000} seconds")

        start = System.currentTimeMillis()
        val rightTopHistory = getHistoryForOnlyChange("right_$height")
        executionTime = System.currentTimeMillis() - start
        val rightHistorySize = rightTopHistory.items.size
        Assert.assertEquals(height, rightHistorySize)
        println("Retrieved changes history of $rightHistorySize entries in ${1.0 * executionTime / 1000} seconds")

        val mid = height / 2

        start = System.currentTimeMillis()
        val leftMidHistory = getHistoryForOnlyChange("left_$mid")
        executionTime = System.currentTimeMillis() - start
        val leftMidHistorySize = leftMidHistory.items.size
        Assert.assertEquals(2 * mid - 1, leftMidHistorySize)
        println("Retrieved changes history of $leftMidHistorySize entries in ${1.0 * executionTime / 1000} seconds")

        start = System.currentTimeMillis()
        val rightMidHistory = getHistoryForOnlyChange("right_$mid")
        executionTime = System.currentTimeMillis() - start
        val rightMidHistorySize = rightMidHistory.items.size
        Assert.assertEquals(mid, rightMidHistorySize)
        println("Retrieved changes history of $rightMidHistorySize entries in ${1.0 * executionTime / 1000} seconds")

    }
}