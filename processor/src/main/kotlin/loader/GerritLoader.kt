package loader

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import khttp.get
import loader.git.RepositoryDownloader
import loader.git.setDownloaded
import mu.KotlinLogging
import org.json.JSONArray
import org.json.JSONObject
import org.researchgroup.git2neo.driver.loader.writeLinesToFile
import java.io.File
import java.io.FileWriter
import java.io.IOException

data class ReviewData(val reviewId: String, val owner: String, val project: String,
                      val reviewersAndVotes: Map<String, String>, val commitDatas: Collection<CommitData>)

data class CommitData(val sha: String, val kind: String, val uploaderId: String, val files: Collection<FileData>)
data class FileData(val path: String, val status: String, val isBinary: Boolean,
                    val oldPath: String?, val inserted: Int, val deleted: Int,
                    val sizeDelta: Int, val size: Int)

data class UserInfo(val id: String, val name: String?, val email: String?, val username: String?)

//const val baseUrl = "https://review.openstack.org/"
const val baseUrl = "https://git.eclipse.org/r/"

const val MILLIS_BETWEEN_REQUESTS = 500
const val NO_LIMIT = Integer.MAX_VALUE
const val REVIEWS_LIMIT = 100000

val ECOSYSTEM = "eclipse"
//val ECOSYSTEM = "openstack"

val RESULTS_FOLDER = "${ECOSYSTEM}_results"

var lastRequestTime = 0L

private val log = KotlinLogging.logger("Gerrit Loader")

fun getRepoDirPath(name: String): String {
    return "$RESULTS_FOLDER/$name/repo"
}

fun getDbDirPath(name: String): String {
    return "$RESULTS_FOLDER/$name/neo4jdb"
}

class GerritLoader {
    fun getRemoteUrl(name: String): String {
        return baseUrl + name
    }

    fun dumpProjectResults(results: Collection<ProjectResult>) {
        val lines = mutableListOf(CSV_HEADER)
        lines.addAll(results.map { it.toCsvString() })
        writeLinesToFile(lines, "eclipse_results.csv", null)
    }

    fun dumpProjectReviews(result: VerboseProjectResult) {
        val fileDir = File("$RESULTS_FOLDER/${result.name}/reviews/")
        fileDir.mkdirs()
        val outFile = File(fileDir.absolutePath + "/out.json")
        FileWriter(outFile).use {
            val gson = GsonBuilder().setPrettyPrinting().create()
            gson.toJson(result, it)
        }
    }

    fun processGerritInstance() {
        val rawReviewDataList: MutableList<ReviewData> = ArrayList()

        val startTime = System.currentTimeMillis()

        var shouldContinue = true
        while (shouldContinue) {
            try {
                val chunk = getReviewersData(rawReviewDataList.size)
                rawReviewDataList.addAll(chunk.first)
                log.info("Loaded ${chunk.first.size} changes, total ${rawReviewDataList.size}")
                shouldContinue = chunk.second && rawReviewDataList.size < REVIEWS_LIMIT
            } catch (e: Throwable) {
                log.info { "error downloading reviews: $e" }
                shouldContinue = false
            }
        }

        val now = System.currentTimeMillis()
        log.info("Downloaded data for ${rawReviewDataList.size} reviews in ${(now - startTime) / 1000} s")
        log.info("Download rate: ${rawReviewDataList.size * 1000.0 / (now - startTime)} reviews/s")

        val reviewDataList = rawReviewDataList.take(REVIEWS_LIMIT)

        val userIdsToLoad: MutableSet<String> = HashSet()
        reviewDataList.forEach {
            userIdsToLoad.add(it.owner)
            userIdsToLoad.addAll(it.reviewersAndVotes.keys)
            userIdsToLoad.addAll(it.commitDatas.map { it.uploaderId })
        }

        val userInfos = loadUserInfos(userIdsToLoad)

        val projects = reviewDataList.map { it.project }.toSet()

        val results: MutableList<ProjectResult> = ArrayList()

        val repositoryDownloader = RepositoryDownloader(::getRemoteUrl)
        projects.forEach {
            try {
                val repoDirPath = getRepoDirPath(it)
                val repoDir = repositoryDownloader.ensureRepo(it, repoDirPath)
                if (repoDir == null) {
                    log.error("Failed to download repo $it")
                    return@forEach
                }

                setDownloaded(it)
                val result = processRepoAndReviews(it, repoDir.absolutePath, reviewDataList, userInfos)
                dumpProjectReviews(result)
            } catch (e: Throwable) {
                log.error { "Cannot download repo $it : $e" }
                return@forEach
            }
        }

        val lines = getRevRecCSV(RESULTS_FOLDER)

        lines.forEach {
            println(it)
        }

        writeLinesToFile(lines, "results_revrec_$ECOSYSTEM.csv", null)
    }

    fun throttleRequest(url: String): String {
        var timeout = 30.0
        var payload: String? = null

        var timeSinceLastRequest = System.currentTimeMillis() - lastRequestTime
        if (timeSinceLastRequest < MILLIS_BETWEEN_REQUESTS) {
            val waitMillis = MILLIS_BETWEEN_REQUESTS - timeSinceLastRequest
            log.info("Waiting $waitMillis ms to enforce request rate limit...")
            Thread.sleep(waitMillis)
        }

        while (payload == null) {
            try {
                lastRequestTime = System.currentTimeMillis()
                payload = get(url, timeout = timeout).text.substringAfter(")]}'\n")
            } catch (e: IOException) {
                timeout *= 2
                log.info("Exception with request (" + e.cause + "); trying again with double timeout $timeout s")
            }
        }

        return payload
    }

    fun loadUserInfos(userIds: Collection<String>): Collection<UserInfo> {
        log.info("Loading user infos for ${userIds.size} users...")

        val userInfos: MutableList<UserInfo> = ArrayList()
        userIds.forEach {
            val url = baseUrl + "accounts/$it/detail"
            val text = throttleRequest(url)
            try {
                val jsonObj = JSONObject(text)
                val name = if (jsonObj.has("name")) jsonObj.getString("name") else null
                val email = if (jsonObj.has("email")) jsonObj.getString("email") else null
                val username = if (jsonObj.has("username")) jsonObj.getString("username") else null
                val userInfo = UserInfo(it, name, email, username)
                userInfos.add(userInfo)
                log.info("${userInfo.id} is ${userInfo.name} (${userInfo.email})")
            } catch (e: Throwable) {
                log.error { "Could not parse response: \n$text" }
                log.error(e, { e.cause })

            }
        }
        return userInfos
    }

    fun getReviewersData(offset: Int): Pair<Collection<ReviewData>, Boolean> {
        val url = baseUrl + "changes/?q=status:merged&S=$offset&o=ALL_REVISIONS&o=DETAILED_LABELS"
        var text: String? = throttleRequest(url)

        val jsonObj = JSONArray(text)

        val reviewDataList: MutableList<ReviewData> = ArrayList()

        var moreChanges = false

        jsonObj.forEach {
            val project = (it as JSONObject).getString("project")

            moreChanges = moreChanges || it.has("_more_changes")

            val id = it.getInt("_number").toString()
            val owner = it.getJSONObject("owner").getInt("_account_id").toString()
            val votesPerReviewer: MutableMap<String, String> = HashMap()
            val commitDatas: MutableList<CommitData> = ArrayList()

            val reviewLabels = (it.get("labels") as JSONObject)
            if (!reviewLabels.has("Code-Review")) return@forEach
            val reviewLabelsArrays = reviewLabels.getJSONObject("Code-Review")
            val reviewLabelsArray = if (reviewLabelsArrays.has("all")) reviewLabelsArrays.getJSONArray("all") else emptyList<JsonObject>()
            reviewLabelsArray.forEach {
                val reviewerLabelEntry = it as JSONObject
                votesPerReviewer[reviewerLabelEntry.getInt("_account_id").toString()] = reviewerLabelEntry.getInt("value").toString()
            }

            val revisionsObject = it.getJSONObject("revisions")
            revisionsObject.keys().forEach {
                val dataObject = revisionsObject.getJSONObject(it)
                val kind = dataObject.getString("kind")
                val uploaderId = dataObject.getJSONObject("uploader").getInt("_account_id").toString()

//            val fileDatas: MutableList<FileData> = ArrayList()
//            val filesJSONObject = dataObject.getJSONObject("files")
//            filesJSONObject.keys().forEach { path ->
//                val entry = filesJSONObject.getJSONObject(path)
//
//                val status = if (!entry.has("status")) "M" else entry.getString("status")
//                val isBinary = entry.has("binary")
//                val oldPath = if (!entry.has("old_path")) null else entry.getString("old_path")
//                val linesInserted = if (!entry.has("lines_inserted")) 0 else entry.getInt("lines_inserted")
//                val linesDeleted = if (!entry.has("lines_deleted")) 0 else entry.getInt("lines_deleted")
//                val sizeDelta = entry.getInt("size_delta")
//                val size = entry.getInt("size")
//
//                val fileData = FileData(path, status, isBinary, oldPath, linesInserted, linesDeleted, sizeDelta, size)
//
//
//                fileDatas.add(fileData)
//            }

                //save a bit of space (20x!!!) in the output, not sure paths are needed anyway: will get from Git
                val commitData = CommitData(it, kind, uploaderId, emptyList())
                commitDatas.add(commitData)
            }
            reviewDataList.add(ReviewData(id, owner, project, votesPerReviewer, commitDatas))
        }

        return Pair(reviewDataList, moreChanges)
    }
}


fun writeDataToFile(data: Collection<ReviewData>, filename: String) {
    val writer = File(filename).writer()
    val gson = GsonBuilder().setPrettyPrinting().create()

    gson.toJson(data, writer)

    writer.close()
}

