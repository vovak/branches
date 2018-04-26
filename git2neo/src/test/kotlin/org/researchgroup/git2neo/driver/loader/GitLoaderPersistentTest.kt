package org.researchgroup.git2neo.driver.loader

import org.junit.After
import org.junit.Assert
import org.junit.Before
import org.neo4j.graphdb.factory.GraphDatabaseFactory
import org.researchgroup.git2neo.driver.CommitIndex
import org.researchgroup.git2neo.driver.loader.util.cleanUnpackedRepos
import org.researchgroup.git2neo.driver.loader.util.removeDir
import org.researchgroup.git2neo.driver.loader.util.unzipRepo
import org.researchgroup.git2neo.model.Action
import org.researchgroup.git2neo.model.CommitId
import java.io.File


class GitLoaderPersistentTest {
    lateinit var myIndex: CommitIndex
    val testDbPath = "./neo4jdb_test"

    @Before
    fun initIndex() {
        val graphDb = GraphDatabaseFactory()
                .newEmbeddedDatabaseBuilder(File(testDbPath))
                .newGraphDatabase()

        myIndex = CommitIndex(graphDb, "persistent db test:")
    }

    @After
    fun clean() {
        myIndex.dispose()
        cleanUnpackedRepos()
        removeDir(testDbPath)
    }

    private fun loadRepo(name: String) {
        val repo = unzipRepo(name)
        val loader = GitLoader(myIndex)
        loader.loadGitRepo(repo.absolutePath)
    }

//    @Test
    fun testLargerHistory5kCommits() {
        loadRepo("webpack")
        val history = myIndex.getChangesHistoriesForCommit(CommitId("000b34e0c2a23563de9b0e862215846deb3710e7"))
        Assert.assertTrue(history.isNotEmpty())
        history[0].items.forEach{println(it.commitInfo.id)}
        Assert.assertEquals(35, history[0].items.size)
        Assert.assertEquals(1, history[0].items.filter { it.action == Action.CREATED }.size)
    }

    //    @Test
    fun testLargerHistory50kCommits() {
        loadRepo("git")
    }
}