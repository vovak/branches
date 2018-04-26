package org.researchgroup.git2neo.driver

import org.neo4j.graphdb.Direction
import org.neo4j.graphdb.GraphDatabaseService
import org.neo4j.graphdb.Node
import org.neo4j.graphdb.RelationshipType
import org.neo4j.graphdb.traversal.Evaluation
import org.neo4j.graphdb.traversal.TraversalDescription
import org.neo4j.graphdb.traversal.Uniqueness
import java.util.*
import kotlin.collections.HashSet

class RelatedChangeFinder(val db: GraphDatabaseService) {
    data class ChangeConnections(val parentsPerChange: Map<Long, Collection<Long>>)

    fun getCommitNodesPerChangedPaths(paths: Collection<String>): Map<String, Collection<Long>> {
        val result: MutableMap<String, Collection<Long>> = HashMap()
        paths.forEach {
            val idsForPaths = db.findNodes(CHANGE, "path", it).map { it.getCommit().id }.asSequence().toList()
            result[it] = HashSet(idsForPaths)
        }
        return result
    }

    fun getPathParentsTraversalDescription(commitNodeId: Long,
                                           candidateNodes: Collection<Long>,
                                           relationshipType: RelationshipType): TraversalDescription {
        return db.traversalDescription()
                .uniqueness(Uniqueness.NODE_GLOBAL)
                .relationships(relationshipType, Direction.OUTGOING)
                .evaluator {
                    if (it.endNode().id == commitNodeId) return@evaluator Evaluation.EXCLUDE_AND_CONTINUE
                    if (candidateNodes.contains(it.endNode().id)) return@evaluator Evaluation.INCLUDE_AND_PRUNE
                    return@evaluator Evaluation.EXCLUDE_AND_CONTINUE
                }
    }

    fun getChangeConnections(commitNode: Node, relationshipType: RelationshipType): ChangeConnections {
        val paths: MutableSet<String> = HashSet()

        val changeNodes = commitNode.getChanges()
        changeNodes.forEach {
            paths.add(it.getPath())
            val oldPath = it.getOldPath()
            if (oldPath != null) paths.add(oldPath)
        }

        val parentCandidates = getCommitNodesPerChangedPaths(paths)
        val parentNodesPerNode: MutableMap<Long, List<Long>> = HashMap()
        changeNodes.forEach {
            val candidates: MutableSet<Long> = HashSet()
            val path = it.getPath()
            val oldPath = it.getOldPath()

            candidates.addAll(parentCandidates[path] ?: emptySet())
            if (oldPath != null) candidates.addAll(parentCandidates[oldPath] ?: emptySet())

            val parentsIterator = getPathParentsTraversalDescription(commitNode.id, candidates, relationshipType).traverse(commitNode).nodes()
            val parents: MutableList<Long> = ArrayList()

            //todo id memoization
            parentsIterator.forEach{
                val parentChanges = it.getChanges().filter { it.getPath() == path || it.getPath() == oldPath }
                parents.addAll(parentChanges.map { it.id })
            }

            parentNodesPerNode[it.id] = parents
        }

        return ChangeConnections(parentNodesPerNode)
    }
}