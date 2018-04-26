package org.researchgroup.git2neo.driver

import org.apache.commons.lang3.SerializationUtils
import org.neo4j.graphdb.*
import org.neo4j.graphdb.traversal.Uniqueness
import org.researchgroup.git2neo.model.*
import org.researchgroup.git2neo.util.use
import java.util.*
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.system.measureTimeMillis


/**
 * @author
 * @since 17/11/16
 */

val COMMIT: Label = Label { "commit" }
val CHANGE: Label = Label { "change" }
val PARENT: RelationshipType = RelationshipType { "PARENT" }
val FIRST_PARENT: RelationshipType = RelationshipType { "FIRST_PARENT" }
val CONTAINS: RelationshipType = RelationshipType { "CONTAINS" }

// do not split processing into multiple jobs if there are too little changes
val SINGLE_THREAD_CHANGES = 100

//TODO check node type (it should not be possible to call *ChangeNode*.getChanges())

fun Node.getChanges(): List<Node> {
    return this.relationships.filter { it.isType(CONTAINS) }.map { it.endNode }
}

fun Node.getCommit(): Node {
    val startNodes = this.relationships.filter { it.isType(CONTAINS) }.map { it.startNode }
    assert(startNodes.size == 1)
    return startNodes.first()
}

fun Node.getAction(): Action {
    return Action.valueOf(getProperty("action") as String)
}

fun Node.getOldPath(): String? {
    if (!this.hasProperty("oldPath")) return null
    return this.getProperty("oldPath") as String
}

fun Node.getPath(): String {
    return this.getProperty("path") as String
}

fun Node.getCommitId(): String {
    return this.getProperty("commitId") as String
}


class CommitIndex(val db: GraphDatabaseService, val logPrefix: String) : CommitStorage {

    //Should be called after using to prevent memory leaks
    fun dispose() {
        println("$logPrefix: Shutting down the db...")
        db.shutdown()
    }

    fun withDb(block: () -> Unit) {
        db.beginTx().use({ tx: Transaction ->
            block.invoke()
            tx.success()
            tx.close()
        })
    }

    init {
        withDb {
            val commitIndexAbsent = db.schema().getIndexes(COMMIT).none()

            if (commitIndexAbsent) db.schema().indexFor(COMMIT).on("id").create()
            val changeIndexAbsent = db.schema().getIndexes(CHANGE).none()

            if (changeIndexAbsent) {
                db.schema().indexFor(CHANGE).on("path").create()
                db.schema().indexFor(CHANGE).on("id").create()
            }
        }
    }

    private fun findOrCreateCommitNode(id: CommitId): Node {
        val result: Node?
        val node = db.findNode(COMMIT, "id", id.idString)
        if (node == null) {
            val newNode = db.createNode(COMMIT)
            newNode.addLabel(COMMIT)
            newNode.setProperty("id", id.idString)
            result = newNode
        } else result = node
        if (result == null) throw Exception("Cannot get node: exception occurred during transaction")
        return result
    }

    fun addChangeNode(commitNode: Node, change: FileRevision) {
        assert(commitNode.hasLabel(COMMIT))
        val changeNode = db.createNode(CHANGE)
        changeNode.setProperty("id", change.id.stringId())
        changeNode.setProperty("action", change.action.name)
        changeNode.setProperty("path", change.path)
        if (change.oldPath != null) {
            changeNode.setProperty("oldPath", change.oldPath)
        }
        changeNode.setProperty("commitId", change.commitInfo.id.stringId())
        commitNode.createRelationshipTo(changeNode, CONTAINS)
    }


    fun getChangeParentsConnections(relatedChangeFinder: RelatedChangeFinder,
                                    commitNodeId: Long,
                                    relationshipType: RelationshipType): RelatedChangeFinder.ChangeConnections {
        val commitNode = db.getNodeById(commitNodeId)
        val connections = relatedChangeFinder.getChangeConnections(commitNode, relationshipType)
        return connections
    }

    fun createChangeConnectionRelations(connections: RelatedChangeFinder.ChangeConnections,
                                        relationshipType: RelationshipType) {
        connections.parentsPerChange.forEach {
            val change = db.getNodeById(it.key)
            val parents = it.value
            parents.forEach { change.createRelationshipTo(db.getNodeById(it), relationshipType) }
        }
    }

    fun doAdd(commit: Commit) {
        val nodeId = commit.info.id
        val node = findOrCreateCommitNode(nodeId)
        node.setProperty("info", SerializationUtils.serialize(commit.info))
        commit.changes.forEach { addChangeNode(node, it) }

        val parentIds = commit.info.parents
        var firstProcessed = false
        parentIds.forEach {
            val parentNode = findOrCreateCommitNode(it)
            node.createRelationshipTo(parentNode, PARENT)
            if (!firstProcessed) {
                firstProcessed = true
                node.createRelationshipTo(parentNode, FIRST_PARENT)
            }
        }
    }

    fun processNodes(nodeIds: List<Long>, chunkIndex: Int, relatedChangeFinder: RelatedChangeFinder, relationshipType: RelationshipType): List<RelatedChangeFinder.ChangeConnections> {
        val total = nodeIds.size
        var done = 0
        val startTime = System.currentTimeMillis()
        var currentStartTime = startTime


        val chunk: MutableList<Long> = ArrayList()
        val windowSize = 200
        val connections: MutableList<RelatedChangeFinder.ChangeConnections> = ArrayList()
        fun processChunk() {
            withDb {
                chunk.forEach {
                    connections.add(getChangeParentsConnections(relatedChangeFinder, it, relationshipType))
                }
            }
            val now = System.currentTimeMillis()
            println("$logPrefix Chunk $chunkIndex: $done/$total done, ${chunk.size} processed in ${1.0 * (now - currentStartTime) / 1000} s")
            currentStartTime = now
            chunk.clear()
        }

        nodeIds.forEach {
            chunk.add(it)
            done++
            if (done % windowSize == 0 && done > 0) {
                processChunk()
            }
        }
        processChunk()
        println("$logPrefix Chunk $chunkIndex: all $done done in ${System.currentTimeMillis() - startTime} ms")
        return connections
    }

    fun getParentConnectionsSearchJob(nodeIds: List<Long>, workerIndex: Int,
                                      relatedChangeFinder: RelatedChangeFinder,
                                      relationshipType: RelationshipType): Callable<List<RelatedChangeFinder.ChangeConnections>> {
        return Callable {
            try {
                return@Callable processNodes(nodeIds, workerIndex, relatedChangeFinder, relationshipType)
            } catch (e: Throwable) {
                throw e
            }
        }
    }

    fun createRelationshipConnectionsInBulk(connections: Collection<RelatedChangeFinder.ChangeConnections>,
                                            relationshipType: RelationshipType) {
        val connectionsPerTransaction = 200
        val total = connections.size

        val startTime = System.currentTimeMillis()
        var currentStartTime = startTime
        val currentChunk: MutableList<RelatedChangeFinder.ChangeConnections> = ArrayList()

        fun flushToDb() {
            println("$logPrefix creating ${currentChunk.size} connection relationships...")
            withDb { currentChunk.forEach { createChangeConnectionRelations(it, relationshipType) } }
            println("$logPrefix done")
            currentChunk.clear()
        }

        connections.forEachIndexed { i, connection ->
            run {
                currentChunk.add(connection)
                if (i >= connectionsPerTransaction && i % connectionsPerTransaction == 0) {
                    flushToDb()
                    val now = System.currentTimeMillis()
                    val msTaken = now - currentStartTime
                    currentStartTime = now
                    println("$logPrefix added $connectionsPerTransaction in $msTaken ms, $i/$total done")
                }
            }
        }
        flushToDb()
    }

    fun updateChangeParentConnectionsForAllNodes() {
        updateChangeParentConnectionsForAllNodes(PARENT)
        updateChangeParentConnectionsForAllNodes(FIRST_PARENT)
    }

    fun updateChangeParentConnectionsForAllNodes(relationshipType: RelationshipType) {
        println("Waiting for db indexes to get online...")
        withDb {
            val time = measureTimeMillis {
                db.schema().awaitIndexesOnline(10, TimeUnit.SECONDS)
            }
            println("Done in $time ms")
        }
        val allNodes: MutableList<Long> = ArrayList()

        withDb {
            db.findNodes(COMMIT).forEach { allNodes.add(it.id) }
        }

        val nCores = Runtime.getRuntime().availableProcessors()
        val nThreads = if (allNodes.size <= SINGLE_THREAD_CHANGES) 1 else nCores / 2

        println("$logPrefix(${relationshipType.name()}) Updating parent connections for all nodes")
        val chunkSizeLimit = if (allNodes.size <= SINGLE_THREAD_CHANGES) allNodes.size else allNodes.size / (nThreads * 5) + 1
        println("$logPrefix $nCores cores available, will use $nThreads threads for change layer build, max $chunkSizeLimit nodes per thread")
        val nodeChunks: MutableList<List<Long>> = ArrayList()

        var currentChunk: MutableList<Long> = ArrayList()
        var currentChunkSize = 0

        fun dumpChunk() {
            nodeChunks.add(currentChunk)
            currentChunk = ArrayList()
            currentChunkSize = 0
        }

        allNodes.forEach { node ->
            currentChunk.add(node)
            currentChunkSize++
            if (currentChunkSize >= chunkSizeLimit) dumpChunk()
        }
        dumpChunk()
        val totalChangesInChunks = nodeChunks.map { it.size }.sum()
        println("$logPrefix $totalChangesInChunks nodes in chunks from ${allNodes.size} total")

        val executorService = Executors.newFixedThreadPool(nThreads)

        val jobs: MutableList<Callable<List<RelatedChangeFinder.ChangeConnections>>> = ArrayList()
        val relatedChangeFinder = RelatedChangeFinder(db)
        nodeChunks.forEachIndexed { index, chunk ->
            val job = getParentConnectionsSearchJob(chunk, index, relatedChangeFinder, relationshipType)
            jobs.add(job)
        }
        val results = executorService.invokeAll(jobs).map { it.get() }

        println("$logPrefix Found parents for all nodes, creating relationships")

        createRelationshipConnectionsInBulk(results.flatten(), relationshipType)

        println("$logPrefix Done creating relationships!")

    }

    override fun add(commit: Commit) {
        add(commit, true)
    }

    fun add(commit: Commit, updateParents: Boolean) {
        withDb {
            doAdd(commit)
        }
        if (updateParents) updateChangeParentConnectionsForAllNodes()
    }

    private fun fullNodeExists(id: CommitId): Boolean {
        var exists = false
        withDb {
            val node = getRawNode(id)
            exists = node!=null && node.hasProperty("info")
        }
        return exists
    }

    fun addIfNotExists(commit: Commit, updateParents: Boolean) {
        if (!fullNodeExists(commit.info.id)) add(commit, updateParents)
    }

    override fun addAll(commits: Collection<Commit>) {
        println("$logPrefix Adding ${commits.size} nodes to db")
        val windowSize = 1000
        val startTime = System.currentTimeMillis()
        var currentStartTime = startTime
        val currentChunk: MutableList<Commit> = ArrayList()

        fun flushToDb() {
            println("$logPrefix flushing $windowSize commits to db...")
            withDb { currentChunk.forEach { doAdd(it) } }
            println("$logPrefix done")
            currentChunk.clear()
        }

        commits.forEachIndexed { i, commit ->
            run {
                currentChunk.add(commit)
                if (i > windowSize && i % windowSize == 0) {
                    flushToDb()
                    val now = System.currentTimeMillis()
                    val msTaken = now - currentStartTime
                    currentStartTime = now
                    println("$logPrefix added $windowSize in $msTaken ms")
                }
            }
        }
        flushToDb()
        val totalMs = System.currentTimeMillis() - startTime
        println("$logPrefix added all ${commits.size} nodes in $totalMs ms")

        updateChangeParentConnectionsForAllNodes()
    }

    fun Node.toFileRevision(commitInfo: CommitInfo): FileRevision {
        val hasOldPath = this.hasProperty("oldPath")
        return FileRevision(
                FileRevisionId(this.getProperty("id") as String),
                this.getProperty("path") as String,
                if (hasOldPath) this.getProperty("oldPath") as String else null,
                commitInfo,
                Action.valueOf(this.getProperty("action") as String),
                this.getParentRevisions()
        )
    }

    fun Node.toFileRevision(): FileRevision {
        val hasOldPath = this.hasProperty("oldPath")
        return FileRevision(
                FileRevisionId(this.getProperty("id") as String),
                this.getProperty("path") as String,
                if (hasOldPath) this.getProperty("oldPath") as String else null,
                SerializationUtils.deserialize(this.getCommit().getProperty("info") as ByteArray),
                Action.valueOf(this.getProperty("action") as String),
                this.getParentRevisions()
        )
    }

    fun Node.getParentRevisions(): Collection<FileRevisionId> {
        return this.getRelationships(PARENT, org.neo4j.graphdb.Direction.OUTGOING).map {
            FileRevisionId(it.endNode.getProperty("id") as String)
        }
    }

    fun Node.toCommit(): Commit {
        val changeNodes = this.relationships.filter { it.isType(CONTAINS) }.map { it.endNode }

        val commitInfo: CommitInfo = SerializationUtils.deserialize(this.getProperty("info") as ByteArray)
        return Commit(
                commitInfo,
                changeNodes.map { it.toFileRevision(commitInfo) }
        )
    }

    override fun get(id: CommitId): Commit? {
        var result: Commit? = null
        withDb {
            val node = getRawNode(id)
            if (node != null) result = node.toCommit()
        }
        return result
    }

    fun getRawNode(id: CommitId): Node? {
        return db.findNode(COMMIT, "id", id.idString)
    }

    fun getCommitHistory(head: Id<Commit>): History<Commit> {
        return getCommitHistory(head, false)
    }

    fun getCommitHistory(head: Id<Commit>, firstParentOnly: Boolean): History<Commit> {
        val commits: MutableList<Commit> = ArrayList()
        val traverseType = if (firstParentOnly) FIRST_PARENT else PARENT
        withDb {
            val headNode = db.findNode(COMMIT, "id", head.stringId())
            val traversal = db.traversalDescription().depthFirst()
                    .relationships(traverseType, Direction.OUTGOING)
                    .uniqueness(Uniqueness.NODE_GLOBAL)
            val result = traversal.traverse(headNode)
            result.nodes().forEach { commits.add(it.toCommit()) }
        }
        return History(commits)
    }

    fun getChangesHistory(head: Id<FileRevision>): History<FileRevision> {
        return getChangesHistory(head, false)
    }

    fun getChangesHistory(head: Id<FileRevision>, firstParent: Boolean): History<FileRevision> {
        val changes: MutableList<FileRevision> = ArrayList()
        val relationshipType = if (firstParent) FIRST_PARENT else PARENT
        withDb {
            val headNode = db.findNode(CHANGE, "id", head.stringId())
            val traversal = db.traversalDescription().depthFirst().relationships(relationshipType, Direction.OUTGOING).uniqueness(Uniqueness.NODE_GLOBAL)
            val result = traversal.traverse(headNode)
            result.nodes().forEach { changes.add(it.toFileRevision()) }
        }
        return History(changes)
    }

    fun getChangesHistoriesForCommit(head: Id<Commit>): List<History<FileRevision>> {
        return getChangesHistoriesForCommit(head, false)
    }

    fun getChangesHistoriesForCommit(head: Id<Commit>, firstParent: Boolean): List<History<FileRevision>> {
        val result: MutableList<History<FileRevision>> = ArrayList()
        val relationshipType = if (firstParent) FIRST_PARENT else PARENT
        withDb {
            val headCommitNode = db.findNode(COMMIT, "id", head.stringId())
            if (headCommitNode == null) {
                println("Commit ${head.stringId()} not found in Git2Neo!")
                return@withDb
            }
            val changeNodes = headCommitNode.getChanges()
            changeNodes.forEach {
                val traversal = db.traversalDescription().depthFirst().relationships(relationshipType, Direction.OUTGOING).uniqueness(Uniqueness.NODE_GLOBAL)
                val history = History(traversal.traverse(it).nodes().map { it.toFileRevision() })
                result.add(history)
            }
        }
        return result
    }

}

