package loader.git

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.lib.TextProgressMonitor
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.transport.RefSpec
import org.slf4j.LoggerFactory
import org.researchgroup.git2neo.util.use
import java.io.File
import java.io.PrintWriter

private val log = LoggerFactory.getLogger("Repo downloader")!!

class RepositoryDownloader(val remoteUrlProducer: (String) -> (String)) {
    fun cloneRepo(name: String, repoFolderName: String): File? {
        val remoteUrl = remoteUrlProducer.invoke(name)
        println(remoteUrl)
        val localPath = File(repoFolderName)
        localPath.mkdirs()
        log.info("Cloning from $remoteUrl to $localPath")
        var repoDir: File? = null

        val repo = Git.cloneRepository()
                .setURI(remoteUrl)
                .setCloneAllBranches(true)
                .setProgressMonitor(TextProgressMonitor(PrintWriter(System.out)))
                .setDirectory(localPath).call()

        repo.use {
            log.info("Have a local repo at " + it.repository.directory)
            repoDir = it.repository.directory
        }
        return repoDir
    }

    fun fetchRepo(name: String, repoDirPath: String): Repository {
        val repoDir = File(repoDirPath)
        val repoBuilder = FileRepositoryBuilder()
        val repo = repoBuilder
                .setGitDir(repoDir)
                .readEnvironment()
                .findGitDir()
                .setMustExist(true)
                .build()

        val git = Git(repo)

        log.info("Listing remote refs...")
        val remoteRefs = git.lsRemote().call()
        log.info("${remoteRefs.size} remote refs. Fetching...")

        git.fetch().setRefSpecs(RefSpec("+refs/*:refs/remotes/origin/*"))
                .setProgressMonitor(TextProgressMonitor(PrintWriter(System.out)))
                .call()

        git.close()
        return repo
    }

    fun ensureRepo(name: String, repoDirPath: String): File? {
        val localDir = File(repoDirPath)
        if (!localDir.exists()) {
            log.info("Local repository $repoDirPath doesn't exist, will clone now")
            cloneRepo(name, repoDirPath)
        } else {
            log.info("Found local repository, fetching...")
        }

        fetchRepo(name, localDir.absolutePath + "/.git")
        println("Repo dir: ${localDir.absolutePath}")
        return localDir
    }

}