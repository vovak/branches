package org.researchgroup.git2neo.driver.loader

import org.junit.After
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import org.neo4j.test.TestGraphDatabaseFactory
import org.researchgroup.git2neo.driver.CommitIndex
import org.researchgroup.git2neo.driver.loader.util.cleanUnpackedRepos
import org.researchgroup.git2neo.driver.loader.util.isGitRepo
import org.researchgroup.git2neo.driver.loader.util.unzipRepo
import org.researchgroup.git2neo.model.Action
import org.researchgroup.git2neo.model.CommitId
import java.io.File

class GitLoaderTest {
    lateinit var myIndex: CommitIndex

    @Before
    fun initIndex() {
        val path = "./testdb"
        val db = TestGraphDatabaseFactory().newImpermanentDatabase(File(path))
        myIndex = CommitIndex(db, javaClass.canonicalName)
    }

    @After
    fun clean() {
        println("Disposing of db...")
        myIndex.dispose()
        cleanUnpackedRepos()
    }

    private fun loadRepo(name: String) {
        val repo = unzipRepo(name)
        val loader = GitLoader(myIndex)
        loader.loadGitRepo(repo.absolutePath+"/.git", collectCommits = false, disposeDb = false)
    }

    @Test
    fun testUnzip() {
        val repo = unzipRepo("ima")
        Assert.assertTrue(isGitRepo(repo.absolutePath))
    }

    @Test
    fun testIma() {
        loadRepo("ima")

        val history = myIndex.getChangesHistoriesForCommit(CommitId("1cb7a8f941790cbe4b56bae135cda108962b28dd"))
        println(history)
        Assert.assertTrue(history.isNotEmpty())
        Assert.assertEquals(20, history[0].items.size)
        Assert.assertTrue(history[0].items.any { it.action == Action.CREATED })
    }

    @Test
    fun testLinearHistory() {
        loadRepo("repo1")

        val history = myIndex.getChangesHistoriesForCommit(CommitId("d8d4a6fa7cde15cd974e0d765b2a54619f8993a9"))
        println(history)
        Assert.assertTrue(history.isNotEmpty())
        Assert.assertEquals(3, history[0].items.size)
        Assert.assertTrue(history[0].items.any { it.action == Action.CREATED })
    }

    @Test
    fun testHistoryWithMergeAndNonTrivialConflict() {
        loadRepo("repo2")

        val history = myIndex.getChangesHistoriesForCommit(CommitId("13e7d745549489fb89b0fd7e63b358a03e32bcbf"))
        println(history)
        Assert.assertTrue(history.isNotEmpty())
        //During the merge, the file was modified. It counts as an edit.
        Assert.assertEquals(5, history[0].items.size)
        Assert.assertTrue(history[0].items.any { it.action == Action.CREATED })
    }

    @Test
    fun testFirstParentHistoryWithMergeAndNonTrivialConflict() {
        loadRepo("repo2")

        val history = myIndex.getChangesHistoriesForCommit(CommitId("13e7d745549489fb89b0fd7e63b358a03e32bcbf"), true)
        println(history)
        Assert.assertTrue(history.isNotEmpty())
        //During the merge, the file was modified. It counts as an edit.
        Assert.assertEquals(4, history[0].items.size)
        Assert.assertTrue(history[0].items.any { it.action == Action.CREATED })
    }

    @Test
    fun testHistoryWithMergeAndNoConflict() {
        loadRepo("repo4")

        val history = myIndex.getChangesHistoriesForCommit(CommitId("08267689cbc24080c9bf655b3bccfeb5fecc4bcd"))
        println(history)
        Assert.assertTrue(history.isNotEmpty())
        //During the merge, the version from master was accepted. It does NOT count as an edit.
        Assert.assertEquals(4, history[0].items.size)
        Assert.assertTrue(history[0].items.any { it.action == Action.CREATED })
    }

    @Test
    fun testFirstParentHistoryWithMergeAndNoConflict() {
        loadRepo("repo4")

        val history = myIndex.getChangesHistoriesForCommit(CommitId("08267689cbc24080c9bf655b3bccfeb5fecc4bcd"),
                true)
        println(history)
        Assert.assertTrue(history.isNotEmpty())
        //During the merge, the version from master was accepted. It does NOT count as an edit.
        Assert.assertEquals(3, history[0].items.size)
        Assert.assertTrue(history[0].items.any { it.action == Action.CREATED })
    }

    @Test
    fun testHistoryWithMergeAndTrivialConflict() {
        loadRepo("repo3")

        val history = myIndex.getChangesHistoriesForCommit(CommitId("94abe6763cb0c1eb0b131ef11af23611701b6c20"))
        println(history)
        Assert.assertTrue(history.isNotEmpty())
        //During the merge, changes from two branches were merged into a new version of the file.
        //Content of these changes was not created during the merge, so ideally it should not count as a change.
        //
        //However, as the file is not similar to any of the parents, it is technically a change. (<--- NOT TRUE)
        //Moreover, it impossible to detect without peeking into contents, which is an expensive procedure.
        //
        //One way around it is to completely ignore modifications from merge commits in history.
        //It's a valid option under assumption that merge commit modifications are designed solely to resolve conflicts.
        Assert.assertEquals(6, history[0].items.size)
        Assert.assertTrue(history[0].items.any { it.action == Action.CREATED })
    }

    @Test
    fun testHistoryWithRename() {
        loadRepo("repo5")

        val history = myIndex.getChangesHistoriesForCommit(CommitId("820ab8873d6689e879cd2ba97f5c5cd6db09f82f"))
        println(history)
        Assert.assertTrue(history.isNotEmpty())
        Assert.assertEquals(4, history[0].items.size)
        Assert.assertTrue(history[0].items.any { it.action == Action.CREATED })
    }

    @Test
    fun testPathFiltering() {
        //there should not be an entry for "src/", but should be one for "src/file.txt"
        loadRepo("repo6")

        val histories = myIndex.getChangesHistoriesForCommit(CommitId("cd0a5dd1fee499d0bc5308cab1d59afc82958628"))
        println(histories)
        Assert.assertEquals(1, histories.size)
        Assert.assertEquals(2, histories[0].items.size)
        Assert.assertTrue(histories[0].items.any { it.action == Action.CREATED })
        val filename = histories[0].items.first().path
        Assert.assertEquals("src/file.txt", filename)
    }

    @Test //https://github.com/***/git2neo/issues/1
    fun testPostfixNamesPathFiltering1() {
        loadRepo("repo9")

        val histories = myIndex.getChangesHistoriesForCommit(CommitId("91e99c3266ebaca77cfc14266db78a4a19369053"))
        Assert.assertEquals(2, histories.size)
        Assert.assertTrue(histories[0].items.any { it.action == Action.CREATED })
        Assert.assertTrue(histories[1].items.any { it.action == Action.CREATED })
    }

    @Test //https://github.com/***/git2neo/issues/1
    fun testPostfixNamesPathFiltering2() {
        loadRepo("repo10")

        val histories = myIndex.getChangesHistoriesForCommit(CommitId("02c63f65f2fdc35c9df199dd2573c883f1e4c961"))
        Assert.assertEquals(3, histories.size)
        Assert.assertTrue(histories[0].items.any { it.action == Action.CREATED })
        Assert.assertTrue(histories[1].items.any { it.action == Action.CREATED })
        Assert.assertTrue(histories[2].items.any { it.action == Action.CREATED })
    }

    fun testPathFiltering2() {
        Assert.fail()
        //TODO come up with a failing testcase (/dev/null paths, old paths, etc)
    }
}
