package org.researchgroup.git2neo.util

import org.researchgroup.git2neo.model.FileRevisionId

fun getFileRevisionId(commit: String, path: String): FileRevisionId {
    return FileRevisionId("$commit#$path")
}