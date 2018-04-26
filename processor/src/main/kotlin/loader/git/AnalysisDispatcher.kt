package loader.git

import loader.git.apriori.CP_ENTRY_CSV_HEADER
import loader.git.apriori.ChangePredictionProjectSummary
import org.researchgroup.git2neo.driver.loader.writeLinesToFile
import java.nio.file.Files
import java.nio.file.Paths
import java.util.*


fun downloadRepos(names: Collection<String>, urlProducer: (String)->(String)) {
    names.forEach {
        if (isDownloaded(it)) {
            println("Project $it is already downloaded!")
            return@forEach
        }
        try {
            println("Downloading project $it...")
            if (GitRepoProcessor().downloadRepo(it, urlProducer) != null) {
                setDownloaded(it)
            }
        } catch (e: Throwable) {
            println(e)
        }
    }
}

fun skipLargeRepos(names: Collection<String>, limit: Int) {
    names.forEach {
        skipIfTooLong(it, limit)
    }
}

fun loadReposToDb(names: Collection<String>) {
    names.forEach {
        if (isDbLoaded(it)) {
            println("Project $it is already loaded to db, skipping")
            return@forEach
        }
        try {
            val repoInfo = GitRepoProcessor().loadRepoToDb(it)
            writeRepoInfo(it, repoInfo)
            setDbLoaded(it)
        } catch (e: Throwable) {
            println(e)
        }
    }
}

fun processRepos(names: Collection<String>): Collection<ChangePredictionProjectSummary> {
    val result: MutableCollection<ChangePredictionProjectSummary> = ArrayList()
    names.forEach {
        val repoInfo = readRepoInfo(it)
        if (repoInfo == null) {
            println("Not found repo info for project $it")
            return@forEach
        }
        try {
            println("Analyzing repo $it...")
            val repositoryData = GitRepoProcessor().analyzeRepo(it, repoInfo)
            if(repositoryData != null) result.add(repositoryData.changePredictionSummary)
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }
    return result
}

data class NameAndCount(val name: String, val count: Int)

fun getGithubProjectNames(count: Int): List<String> {
    val lines = Files.readAllLines(Paths.get("github_projects.csv")).drop(1)
    val projects: MutableList<NameAndCount> = ArrayList()
    lines.forEach {
        val projectName = it.split(",")[0].substringAfterLast("repos/")
        val commitsCount = it.split(",")[1].toInt()
        projects.add(NameAndCount(projectName, commitsCount))
    }
    return projects.filter { it.count in 1..1000 }.map { it.name }.shuffled(Random(12)).take(count)
}

fun getApacheProjectNames(count: Int): List<String> {
    val lines = Files.readAllLines(Paths.get("apache_projects.csv")).shuffled().map { it.trim() }.map { it.substringBefore(".git") }
    return lines.take(count)
}

fun analyzeSample() {
    val projectNames = Files.readAllLines(Paths.get("eclipse-sample.txt"))

    println(projectNames)

    val lines = mutableListOf(CP_ENTRY_CSV_HEADER)
    val summaries = processRepos(projectNames.filter { isDownloaded(it) && isDbLoaded(it) && !isSkipped(it) })
    lines.addAll(summaries.map { it.entries.map { it.toCsvLine() } }.flatten())

    writeLinesToFile(lines, "$RESULTS_FOLDER_NAME/results-change-prediction.txt", null)
}


fun main(args: Array<String>) {
    analyzeSample()
    println("Done, feel free to shut down the process.")
}
