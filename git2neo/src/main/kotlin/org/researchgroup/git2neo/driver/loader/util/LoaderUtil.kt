package org.researchgroup.git2neo.driver.loader.util

import java.io.*
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.util.zip.ZipEntry
import java.util.zip.ZipFile


fun getRepoArchivePath(name: String): String = "testData/zipped/$name.zip"
fun getRepoUnpackedPath(): String = "testData/unpacked/"

fun unzipRepo(name: String): File {
    extractFolder(getRepoArchivePath(name), getRepoUnpackedPath())
    return File(getRepoUnpackedPath() + "/$name")
}

fun extractFolder(zipFile: String, extractFolder: String) {
    try {
        val BUFFER = 2048
        val file = File(zipFile)

        val zip = ZipFile(file)

        File(extractFolder).mkdir()
        val zipFileEntries = zip.entries()

        // Process each entry
        while (zipFileEntries.hasMoreElements()) {
            // grab a zip file entry
            val entry = zipFileEntries.nextElement() as ZipEntry
            val currentEntry = entry.name

            val destFile = File(extractFolder, currentEntry)
            //destFile = new File(newPath, destFile.getName());
            val destinationParent = destFile.parentFile

            // create the parent directory structure if needed
            destinationParent.mkdirs()

            if (!entry.isDirectory) {
                val inputStream = BufferedInputStream(zip
                        .getInputStream(entry))
                var currentByte: Int
                // establish buffer for writing file
                val data = ByteArray(BUFFER)

                // write the current file to disk
                val fos = FileOutputStream(destFile)
                val dest = BufferedOutputStream(fos,
                        BUFFER)

                // read and write until last byte is encountered
                currentByte = inputStream.read(data, 0, BUFFER)
                while (currentByte != -1) {
                    dest.write(data, 0, currentByte)
                    currentByte = inputStream.read(data, 0, BUFFER)
                }
                dest.flush()
                dest.close()
                inputStream.close()
            }


        }
    } catch (e: Exception) {
        println("ERROR: " + e.message)
    }

}

fun isGitRepo(path: String): Boolean {
    val gitDataFolder = File(path+File.separator+".git")
    println("Checking if ${gitDataFolder.absolutePath} is a git folder...")
    return gitDataFolder.exists()
}

fun cleanUnpackedRepos() {
    removeDir(getRepoUnpackedPath())
}

fun removeDir(path: String) {
    val dir = Paths.get(path)
    Files.walkFileTree(dir, object : SimpleFileVisitor<Path>() {
        @Throws(IOException::class)
        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
            Files.delete(file)
            return FileVisitResult.CONTINUE
        }

        @Throws(IOException::class)
        override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
            Files.delete(dir)
            return FileVisitResult.CONTINUE
        }
    })
}

fun pathContainsSubPath(path: String, subPath: String): Boolean {
    if(path == subPath) return true
    val effectiveDirPath = if(!subPath.endsWith("/")) "$subPath/" else subPath
    return path.startsWith(effectiveDirPath)
}