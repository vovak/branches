package loader.git

import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.researchgroup.git2neo.util.use
import java.io.File


fun skipIfTooLong(name: String, limit: Int) {
    val repoDir = File(getRepoFolder(name)+"/.git")
    val repoBuilder = FileRepositoryBuilder()
    try {
    val repo = repoBuilder
            .setGitDir(repoDir)
            .readEnvironment()
            .findGitDir()
            .setMustExist(true)
            .build()

    val allRefs = repo.allRefs.filterKeys { it.contains("heads/") || it.contains("changes/") }.values

    var count = 0

    repo.use {
        val revWalk = RevWalk(repo)
        revWalk.use {
            allRefs.forEach {
                revWalk.markStart(revWalk.parseCommit(it.objectId))
            }
            revWalk.forEach {
                count++
                if (count > limit) {
                    println("Project $name is too large (limit $limit commits, skipping...)")
                    setSkip(name)
                    return
                }
            }
        }
    } } catch (e: Throwable) {
        println("Repo $name corrupted: $e")
    }
}