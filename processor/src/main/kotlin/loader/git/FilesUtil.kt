package loader.git

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import org.researchgroup.git2neo.driver.loader.GitLoader
import java.io.*
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

val GITHUB_RESULTS_FOLDER_NAME = "git_results"
val APACHE_RESULTS_FOLDER_NAME = "apache_results"
val OPENSTACK_RESULTS_FOLDER_NAME = "openstack_results"
val ECLIPSE_RESULTS_FOLDER_NAME = "eclipse_results"

val RESULTS_FOLDER_NAME = ECLIPSE_RESULTS_FOLDER_NAME

fun getRepoFolder(projectName: String): String {
    return "$RESULTS_FOLDER_NAME/$projectName/repo"
}

fun getDbFolder(projectName: String): String {
//    return "$RESULTS_FOLDER_NAME/$projectName/neo4jdb"
    return "testneo4jdb_$RESULTS_FOLDER_NAME/$projectName/neo4jdb"
}

fun getDownloadedFlagFileName(projectName: String): String {
    return "$RESULTS_FOLDER_NAME/$projectName/downloaded.flag"
}

fun getDbLoadedFlagFileName(projectName: String): String {
    return "$RESULTS_FOLDER_NAME/$projectName/dbLoaded.flag"
}

fun getSkipFlagFileName(projectName: String): String {
    return "$RESULTS_FOLDER_NAME/$projectName/skip.flag"
}

fun isDownloaded(projectName: String): Boolean {
    return File(getDownloadedFlagFileName(projectName)).exists()
}

fun isDbLoaded(projectName: String): Boolean {
    return File(getDbLoadedFlagFileName(projectName)).exists()
}

fun isSkipped(projectName: String): Boolean {
    return File(getSkipFlagFileName(projectName)).exists()
}

fun setDownloaded(projectName: String) {
    File(getDownloadedFlagFileName(projectName)).createNewFile()
}

fun setDbLoaded(projectName: String) {
    File(getDbLoadedFlagFileName(projectName)).createNewFile()
}

fun setSkip(projectName: String) {
    File(getSkipFlagFileName(projectName)).createNewFile()
}

fun getRepoInfoFilePath(projectName: String): String {
    return "$RESULTS_FOLDER_NAME/$projectName/repository_info.json"
}

fun getLinearFileHistoriesFilePath(projectName: String): String {
    return "$RESULTS_FOLDER_NAME/$projectName/linear_histories.csv"
}

fun getFullFileHistoriesFilePath(projectName: String): String {
    return "$RESULTS_FOLDER_NAME/$projectName/full_histories.csv"
}

fun getFileHistoriesArchivePath(projectName: String): String {
    return "$RESULTS_FOLDER_NAME/$projectName/histories_${projectName.replace("/", "_")}.zip"
}

fun writeRepoInfo(projectName: String, repoInfo: GitLoader.RepositoryInfo) {
    val file = File(getRepoInfoFilePath(projectName))
    FileWriter(file).use {
        GsonBuilder().setPrettyPrinting().create().toJson(repoInfo, it)
    }
}

fun readRepoInfo(projectName: String): GitLoader.RepositoryInfo? {
    println("Reading repo info for $projectName: file path ${getRepoInfoFilePath(projectName)}")
    var result: GitLoader.RepositoryInfo? = null
    try {
        result = Gson().fromJson(FileReader(getRepoInfoFilePath(projectName)), GitLoader.RepositoryInfo::class.java)
    } catch (e: Throwable) {
        e.printStackTrace()
    }
    println("Done!")
    return result
}

fun zipFiles(paths: Collection<String>, outputFilePath: String) {
    val archiveFile = File(outputFilePath)
    val buffer = ByteArray(1024)
    ZipOutputStream(FileOutputStream(archiveFile)).use {
        paths.forEach { path ->
            val zipEntry = ZipEntry(path.substringAfterLast("/"))
            it.putNextEntry(zipEntry)
            val input = FileInputStream(path)
            var len = input.read(buffer)
            while(len > 0) {
                it.write(buffer, 0, len)
                len = input.read(buffer)
            }
            input.close()
            it.closeEntry()
        }
    }
}

fun deleteFile(path: String) {
    File(path).delete()
}

