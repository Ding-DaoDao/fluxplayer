package com.fluxplayer.app.core.data.cloud

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** 参数向量取自官方网页上传模块的初始化与提交分支。 */
class TianyiUploadProtocolTest {
    @Test
    fun smallFileUsesImmediateHashCheckAndDoesNotRequestOverwrite() {
        assertEquals(
            linkedMapOf("parentFolderId" to "-11", "fileName" to "%E6%B5%8B%E8%AF%95%20%2B!~*'().txt", "fileSize" to "7", "sliceSize" to "10", "fileMd5" to "md5", "sliceMd5" to "md5"),
            TianyiUploadProtocol.initialize("", "测试 +!~*'().txt", 7, 10, "md5", "md5"),
        )
        val commit = TianyiUploadProtocol.commit("upload", "md5", "md5", 1)
        assertEquals("0", commit["lazyCheck"])
        assertFalse(commit.containsKey("opertype"))
    }

    @Test
    fun multipartDefersHashesUntilCheckAndCommit() {
        assertEquals(
            linkedMapOf("parentFolderId" to "folder", "fileName" to "video.mp4", "fileSize" to "11", "sliceSize" to "10", "lazyCheck" to "1"),
            TianyiUploadProtocol.initialize("folder", "video.mp4", 11, 10, "md5", "slice"),
        )
        assertEquals(
            linkedMapOf("uploadFileId" to "upload", "lazyCheck" to "1", "fileMd5" to "md5", "sliceMd5" to "slice"),
            TianyiUploadProtocol.commit("upload", "md5", "slice", 2),
        )
    }

    @Test
    fun plainAndEncodedHeadersPreservePlusAndLiteralPercentSequences() {
        val raw = "Authorization=Basic a+b==&x-custom=value%2Fpart&Content-Type=text/plain"
        assertEquals("Basic a+b==", TianyiUploadProtocol.headers(raw)["Authorization"])
        assertEquals("value%2Fpart", TianyiUploadProtocol.headers(raw)["x-custom"])
        assertEquals("Basic a+b==", TianyiUploadProtocol.headers("Authorization%3DBasic%20a+b%3D%3D")["Authorization"])
    }

    @Test
    fun officialAndLegacyCompletionShapesRemainReadable() {
        assertEquals("123", TianyiUploadProtocol.completedId(JSONObject("""{"file":{"userFileId":123}}""")))
        assertEquals("file", TianyiUploadProtocol.completedId(JSONObject("""{"data":{"fileId":"file"}}""")))
        assertEquals("", TianyiUploadProtocol.completedId(JSONObject("""{"file":{"userFileId":null}}""")))
    }
}
