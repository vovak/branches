package org.researchgroup.git2neo.driver

import org.neo4j.graphdb.GraphDatabaseService

/**
 * @author
 * @since 22/11/16
 */
class CommitIndexFactory {
    fun loadCommitIndex(db: GraphDatabaseService, logPrefix: String): CommitIndex {
        return CommitIndex(db, logPrefix)
    }

}
