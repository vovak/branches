package org.researchgroup.git2neo.driver.loader

import org.eclipse.jgit.dircache.DirCache
import org.eclipse.jgit.dircache.DirCacheEntry
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.ObjectInserter
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.merge.MergeFormatter
import org.eclipse.jgit.merge.ResolveMerger
import org.eclipse.jgit.merge.ThreeWayMergeStrategy
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevTree
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.util.TemporaryBuffer
import org.researchgroup.git2neo.util.use
import java.io.IOException
import java.nio.charset.StandardCharsets.UTF_8
import java.util.*

object AutoMerger {
    @Throws(IOException::class)
    fun automerge(repo: Repository, rw: RevWalk, commit: RevCommit,
                  mergeStrategy: ThreeWayMergeStrategy, save: Boolean): RevTree? {
        val hash = commit.name()
        val refName = ("refs/cache-automerge/"
                + hash.substring(0, 2)
                + "/"
                + hash.substring(2))
        val ref = repo.refDatabase.exactRef(refName)
        if (ref != null && ref.objectId != null) {
            return rw.parseTree(ref.objectId)
        }

        val m = mergeStrategy.newMerger(repo, true) as ResolveMerger
        repo.newObjectInserter().use { ins ->
            val dc = DirCache.newInCore()
            m.setDirCache(dc)
            m.objectInserter = object : ObjectInserter.Filter() {
                override fun delegate(): ObjectInserter {
                    return ins
                }

                override fun flush() {}

                override fun close() {}
            }

            val couldMerge: Boolean
            try {
                couldMerge = m.merge(*commit.parents)
            } catch (e: IOException) {
                // It is not safe to continue further down in this method as throwing
                // an exception most likely means that the merge tree was not created
                // and m.getMergeResults() is empty. This would mean that all paths are
                // unmerged and Gerrit UI would show all paths in the patch list.
                println("Error attempting automerge $refName")
                return null
            }

            val treeId: ObjectId
            if (couldMerge) {
                treeId = m.resultTreeId

            } else {
                val ours = commit.getParent(0)
                val theirs = commit.getParent(1)
                rw.parseBody(ours)
                rw.parseBody(theirs)
                val oursMsg = ours.shortMessage
                val theirsMsg = theirs.shortMessage

                val oursName = String.format("HEAD   (%s %s)",
                        ours.abbreviate(6).name(),
                        oursMsg.substring(0, Math.min(oursMsg.length, 60)))
                val theirsName = String.format("BRANCH (%s %s)",
                        theirs.abbreviate(6).name(),
                        theirsMsg.substring(0, Math.min(theirsMsg.length, 60)))

                val fmt = MergeFormatter()
                val r = m.mergeResults
                val resolved = HashMap<String, ObjectId>()
                for ((key, p) in r) {
                    TemporaryBuffer.LocalFile(null, 10 * 1024 * 1024).use { buf ->
                        fmt.formatMerge(buf, p, "BASE", oursName, theirsName, UTF_8.name())
                        buf.close()

                        buf.openInputStream().use { `in` -> resolved.put(key, ins.insert(Constants.OBJ_BLOB, buf.length(), `in`)) }
                    }
                }

                val builder = dc.builder()
                val cnt = dc.entryCount
                var i = 0
                while (i < cnt) {
                    var entry = dc.getEntry(i)
                    if (entry.stage == 0) {
                        builder.add(entry)
                        i++
                        continue
                    }

                    val next = dc.nextEntry(i)
                    val path = entry.pathString
                    val res = DirCacheEntry(path)
                    if (resolved.containsKey(path)) {
                        // For a file with content merge conflict that we produced a result
                        // above on, collapse the file down to a single stage 0 with just
                        // the blob content, and a randomly selected mode (the lowest stage,
                        // which should be the merge base, or ours).
                        res.fileMode = entry.fileMode
                        res.setObjectId(resolved[path])

                    } else if (next == i + 1) {
                        // If there is exactly one stage present, shouldn't be a conflict...
                        res.fileMode = entry.fileMode
                        res.setObjectId(entry.objectId)

                    } else if (next == i + 2) {
                        // Two stages suggests a delete/modify conflict. Pick the higher
                        // stage as the automatic result.
                        entry = dc.getEntry(i + 1)
                        res.fileMode = entry.fileMode
                        res.setObjectId(entry.objectId)

                    } else { // 3 stage conflict, no resolve above
                        // Punt on the 3-stage conflict and show the base, for now.
                        res.fileMode = entry.fileMode
                        res.setObjectId(entry.objectId)
                    }
                    builder.add(res)
                    i = next
                }
                builder.finish()
                treeId = dc.writeTree(ins)
            }
            ins.flush()

            if (save) {
                val update = repo.updateRef(refName)
                update.setNewObjectId(treeId)
                update.disableRefLog()
                update.forceUpdate()
            }

            return rw.lookupTree(treeId)
        }
    }
}
