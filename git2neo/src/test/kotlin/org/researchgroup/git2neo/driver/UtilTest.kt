package org.researchgroup.git2neo.driver

import org.junit.Assert
import org.junit.Test
import org.researchgroup.git2neo.driver.loader.util.pathContainsSubPath

class UtilTest {
    @Test
    fun testPathFilterMethod1() {
        Assert.assertTrue(pathContainsSubPath("dir/file.txt", "dir"))
        Assert.assertTrue(pathContainsSubPath("dir/file.txt", "dir/"))
    }

    @Test
    fun testPathFilterMethod2() {
        Assert.assertTrue(pathContainsSubPath("root/dir/file.txt", "root/dir/"))
        Assert.assertTrue(pathContainsSubPath("root/dir/file.txt", "root/dir"))
    }

    @Test
    fun testPathFilterMethod3() {
        Assert.assertFalse(pathContainsSubPath("root/dir/file.txt", "dir/"))
        Assert.assertFalse(pathContainsSubPath("root/dir/file.txt", "dir"))
    }

    @Test
    fun testPathFilterMethod4() {
        Assert.assertFalse(pathContainsSubPath("root/dir/file.txt.txt", "root/dir/file.txt"))
        Assert.assertTrue(pathContainsSubPath("root/dir/file.txt.txt", "root/dir"))
    }


}