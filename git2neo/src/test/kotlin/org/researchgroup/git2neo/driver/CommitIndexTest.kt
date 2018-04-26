package org.researchgroup.git2neo.driver

import org.junit.Assert
import org.junit.Test
import org.researchgroup.git2neo.model.Action
import org.researchgroup.git2neo.model.Commit
import org.researchgroup.git2neo.model.CommitId
import org.researchgroup.git2neo.util.getFileRevisionId
import java.util.*

/**
 * @author
 * @since 17/11/16
 */
class CommitIndexTest : CommitIndexTestBase() {
    @Test
    fun testAddCommit() {
        val index = getIndex()

        Assert.assertNull(index.get(CommitId("0")))
        index.add(createCommit("0", null))

        val nonExistingCommit = index.get(CommitId("unknownIndex"))
        Assert.assertNull(nonExistingCommit)

        val commitFromIndex = index.get(CommitId("0"))
        Assert.assertNotNull(commitFromIndex)

        Assert.assertEquals(index.get(CommitId("0"))?.info?.id, createCommit("0", null).info.id)
    }

    @Test
    fun testAddTwoCommits() {
        val index = getIndex()
        index.add(createCommit("0", null))
        index.add(createCommit("1", "0"))

        val trivialHistory = index.getCommitHistory(CommitId("0"))
        Assert.assertEquals(1, trivialHistory.items.size)

        val fullHistory = index.getCommitHistory(CommitId("1"))
        Assert.assertEquals(2, fullHistory.items.size)
    }

    @Test
    fun testOneMerge() {
        val index = getIndex()
        index.add(createCommit("0", null))
        index.add(createCommit("left", "0"))
        index.add(createCommit("right", "0"))
        index.add(createCommit("merge", listOf("left", "right")))
        index.add(createCommit("head", "merge"))

        val fullHistory = index.getCommitHistory(CommitId("head"))
        Assert.assertEquals(5, fullHistory.items.size)
    }

    @Test
    fun testOneNodeWithChange() {
        val index = getIndex()
        index.add(createCommit("0", null, listOf(Triple(Action.CREATED, "a.txt", null))))
        val commitFromDb = index.get(CommitId("0"))

        Assert.assertEquals(1, commitFromDb!!.changes.size)
    }

    @Test
    fun testTrivialChangesHistory() {
        val index = getIndex()
        index.add(createCommit("0", null, listOf(Triple(Action.CREATED, "a.txt", null))))
        index.add(createCommit("1", "0", listOf(Triple(Action.MODIFIED, "a.txt", null))))
        val headCommit = index.get(CommitId("1"))
        Assert.assertNotNull(headCommit)
        val headCommitChange = headCommit!!.changes.first()

        val changesHistory = index.getChangesHistory(headCommitChange.id)
        Assert.assertEquals(2, changesHistory.items.size)
    }

    @Test
    fun testParentFileRevisionWithTrivialChangesHistory() {
        val index = getIndex()
        index.add(createCommit("0", null, listOf(Triple(Action.CREATED, "a.txt", null))))
        index.add(createCommit("1", "0", listOf(Triple(Action.MODIFIED, "a.txt", null))))
        val headCommit = index.get(CommitId("1"))
        Assert.assertNotNull(headCommit)
        val headCommitChange = headCommit!!.changes.first()

        val parentRevisions = headCommitChange.parentRevisions
        Assert.assertNotNull(parentRevisions)
        Assert.assertEquals(1, parentRevisions!!.size)

        Assert.assertEquals(getFileRevisionId("0", "a.txt").stringId(), parentRevisions.first().id)
    }

    @Test
    fun testTrivialChangesHistoryWithReverseInsertionOrder() {
        val index = getIndex()
        index.add(createCommit("1", "0", listOf(Triple(Action.MODIFIED, "a.txt", null))))
        index.add(createCommit("0", null, listOf(Triple(Action.CREATED, "a.txt", null))))
        val headCommit = index.get(CommitId("1"))
        Assert.assertNotNull(headCommit)
        val headCommitChange = headCommit!!.changes.first()

        val changesHistory = index.getChangesHistory(headCommitChange.id)
        Assert.assertEquals(2, changesHistory.items.size)
    }

    @Test
    fun testTrivialChangesHistoryWithBulkAdd() {
        val index = getIndex()
        val commits = listOf(
                createCommit("0", null, listOf(Triple(Action.CREATED, "a.txt", null))),
                createCommit("1", "0", listOf(Triple(Action.MODIFIED, "a.txt", null)))
        )
        index.addAll(commits)
        val headCommit = index.get(CommitId("1"))
        Assert.assertNotNull(headCommit)
        val headCommitChange = headCommit!!.changes.first()

        val changesHistory = index.getChangesHistory(headCommitChange.id)
        Assert.assertEquals(2, changesHistory.items.size)
    }

    @Test
    fun testLongerLinearChangesHistoryWithOneFile() {
        val index = getIndex()
        index.add(createCommit("0", null, listOf(Triple(Action.CREATED, "a.txt", null))))
        index.add(createCommit("1", "0", listOf(Triple(Action.MODIFIED, "a.txt", null))))
        index.add(createCommit("2", "1", listOf(Triple(Action.MODIFIED, "a.txt", null))))
        index.add(createCommit("3", "2", listOf(Triple(Action.MODIFIED, "a.txt", null))))
        index.add(createCommit("4", "3", listOf(Triple(Action.MODIFIED, "a.txt", null))))
        index.add(createCommit("5", "4", listOf(Triple(Action.MODIFIED, "a.txt", null))))
        val headCommit = index.get(CommitId("5"))
        Assert.assertNotNull(headCommit)
        val headCommitChange = headCommit!!.changes.first()

        val changesHistory = index.getChangesHistory(headCommitChange.id)
        Assert.assertEquals(6, changesHistory.items.size)
    }

    @Test
    fun testLongerLinearChangesHistoryWithTwoFiles() {
        val index = getIndex()
        index.add(createCommit("0", null, listOf(Triple(Action.CREATED, "a.txt", null))))
        index.add(createCommit("1", "0", listOf(Triple(Action.MODIFIED, "a.txt", null), Triple(Action.CREATED, "b.txt", null))))
        index.add(createCommit("2", "1", listOf(Triple(Action.MODIFIED, "a.txt", null), Triple(Action.MODIFIED, "b.txt", null))))
        index.add(createCommit("3", "2", listOf(Triple(Action.MODIFIED, "a.txt", null))))
        index.add(createCommit("4", "3", listOf(Triple(Action.MODIFIED, "b.txt", null))))
        index.add(createCommit("5", "4", listOf(Triple(Action.MODIFIED, "a.txt", null))))
        val headCommit = index.get(CommitId("5"))
        Assert.assertNotNull(headCommit)
        val headCommitChange = headCommit!!.changes.first()

        val changesHistory = index.getChangesHistory(headCommitChange.id)
        Assert.assertEquals(5, changesHistory.items.size)

        val headForB = index.get(CommitId("4"))!!.changes.first()
        val changesForBHistory = index.getChangesHistory(headForB.id)
        Assert.assertEquals(3, changesForBHistory.items.size)
    }

    @Test
    fun testWithRenames() {
        val index = getIndex()
        index.add(createCommit("0", null, listOf(Triple(Action.CREATED, "a.txt", null))))
        index.add(createCommit("1", "0", listOf(Triple(Action.MOVED, "a1.txt", "a.txt"))))
        index.add(createCommit("2", "1", listOf(Triple(Action.MODIFIED, "a1.txt", null))))
        index.add(createCommit("3", "2", listOf(Triple(Action.MODIFIED, "a1.txt", null))))
        index.add(createCommit("4", "3", listOf(Triple(Action.MOVED, "a2.txt", "a1.txt"))))
        index.add(createCommit("5", "4", listOf(Triple(Action.MODIFIED, "a2.txt", null))))

        val beforeHead = index.get(CommitId("4"))
        Assert.assertNotNull(beforeHead)
        val beforeHeadCommitChange = beforeHead!!.changes.first()

        val changesHistoryBeforeHead = index.getChangesHistory(beforeHeadCommitChange.id)
        Assert.assertEquals(5, changesHistoryBeforeHead.items.size)

        val head = index.get(CommitId("5"))
        Assert.assertNotNull(head)
        val headCommitChange = head!!.changes.first()

        val changesHistoryHead = index.getChangesHistory(headCommitChange.id)
        Assert.assertEquals(6, changesHistoryHead.items.size)
    }


    @Test
    fun testWithRenamesMessyInsertionOrder() {
        val index = getIndex()
        index.add(createCommit("1", "0", listOf(Triple(Action.MOVED, "a1.txt", "a.txt"))))
        index.add(createCommit("5", "4", listOf(Triple(Action.MODIFIED, "a2.txt", null))))
        index.add(createCommit("2", "1", listOf(Triple(Action.MODIFIED, "a1.txt", null))))
        index.add(createCommit("4", "3", listOf(Triple(Action.MOVED, "a2.txt", "a1.txt"))))
        index.add(createCommit("0", null, listOf(Triple(Action.CREATED, "a.txt", null))))
        index.add(createCommit("3", "2", listOf(Triple(Action.MODIFIED, "a1.txt", null))))

        val beforeHead = index.get(CommitId("4"))
        Assert.assertNotNull(beforeHead)
        val beforeHeadCommitChange = beforeHead!!.changes.first()

        val changesHistoryBeforeHead = index.getChangesHistory(beforeHeadCommitChange.id)
        Assert.assertEquals(5, changesHistoryBeforeHead.items.size)

        val head = index.get(CommitId("5"))
        Assert.assertNotNull(head)
        val headCommitChange = head!!.changes.first()

        val changesHistoryHead = index.getChangesHistory(headCommitChange.id)
        Assert.assertEquals(6, changesHistoryHead.items.size)
    }

    @Test
    fun testBulkAddWithMergeAddedLater() {
        val index = getIndex()
        val commits: MutableList<Commit> = ArrayList()

        commits.add(createCommit("0_left", null, listOf(Triple(Action.CREATED, "a.txt", null))))
        commits.add(createCommit("1_left", "0_left", listOf(Triple(Action.MODIFIED, "a.txt", null))))
        commits.add(createCommit("2_left", "1_left", listOf(Triple(Action.MODIFIED, "a.txt", null))))
        commits.add(createCommit("3_left", "2_left", listOf(Triple(Action.MODIFIED, "a.txt", null))))
        commits.add(createCommit("4_left", "3_left", listOf(Triple(Action.MODIFIED, "a.txt", null))))
        commits.add(createCommit("5_left", "4_left", listOf(Triple(Action.MODIFIED, "a.txt", null))))

        commits.add(createCommit("0_right", null, listOf(Triple(Action.CREATED, "a.txt", null))))
        commits.add(createCommit("1_right", "0_right", listOf(Triple(Action.MODIFIED, "a.txt", null))))
        commits.add(createCommit("2_right", "1_right", listOf(Triple(Action.MODIFIED, "a.txt", null))))
        commits.add(createCommit("3_right", "2_right", listOf(Triple(Action.MODIFIED, "a.txt", null))))
        commits.add(createCommit("4_right", "3_right", listOf(Triple(Action.MODIFIED, "a.txt", null))))
        commits.add(createCommit("5_right", "4_right", listOf(Triple(Action.MODIFIED, "a.txt", null))))

        commits.add(createCommit("head", "merge", listOf(Triple(Action.MODIFIED, "a.txt", null))))
        commits.add(createCommit("merge", listOf("5_left", "5_right"), emptyList()))
        index.addAll(commits)

        val wholeHistories = index.getChangesHistoriesForCommit(CommitId("head"))
        Assert.assertEquals(1, wholeHistories.size)
        val fileHistory = wholeHistories.first()
        Assert.assertEquals(13, fileHistory.items.size)
    }

    @Test
    fun testFileRevisionParentIdsWithMergeAddedLater() {
        val index = getIndex()
        val commits: MutableList<Commit> = ArrayList()

        commits.add(createCommit("0_left", null, listOf(Triple(Action.CREATED, "a.txt", null))))
        commits.add(createCommit("1_left", "0_left", listOf(Triple(Action.MODIFIED, "a.txt", null))))
        commits.add(createCommit("2_left", "1_left", listOf(Triple(Action.MODIFIED, "a.txt", null))))
        commits.add(createCommit("3_left", "2_left", listOf(Triple(Action.MODIFIED, "a.txt", null))))
        commits.add(createCommit("4_left", "3_left", listOf(Triple(Action.MODIFIED, "a.txt", null))))
        commits.add(createCommit("5_left", "4_left", listOf(Triple(Action.MODIFIED, "a.txt", null))))

        commits.add(createCommit("0_right", null, listOf(Triple(Action.CREATED, "a.txt", null))))
        commits.add(createCommit("1_right", "0_right", listOf(Triple(Action.MODIFIED, "a.txt", null))))
        commits.add(createCommit("2_right", "1_right", listOf(Triple(Action.MODIFIED, "a.txt", null))))
        commits.add(createCommit("3_right", "2_right", listOf(Triple(Action.MODIFIED, "a.txt", null))))
        commits.add(createCommit("4_right", "3_right", listOf(Triple(Action.MODIFIED, "a.txt", null))))
        commits.add(createCommit("5_right", "4_right", listOf(Triple(Action.MODIFIED, "a.txt", null))))

        commits.add(createCommit("head", "merge", listOf(Triple(Action.MODIFIED, "a.txt", null))))
        commits.add(createCommit("merge", listOf("5_left", "5_right"), emptyList()))
        index.addAll(commits)

        val wholeHistories = index.getChangesHistoriesForCommit(CommitId("head"))
        Assert.assertEquals(1, wholeHistories.size)
        val fileHistory = wholeHistories.first()

        val headCommitChange = fileHistory.items.find { it.id == getFileRevisionId("head", "a.txt") }
        Assert.assertNotNull(headCommitChange)

        val headCommitChangeParents = headCommitChange!!.parentRevisions
        Assert.assertNotNull(headCommitChangeParents)

        Assert.assertEquals(2, headCommitChangeParents!!.size)

        val parentChangeIds = headCommitChangeParents.map { it.stringId() }

        Assert.assertTrue(parentChangeIds.contains(getFileRevisionId("5_left", "a.txt").stringId()))
        Assert.assertTrue(parentChangeIds.contains(getFileRevisionId("5_right", "a.txt").stringId()))
    }
}