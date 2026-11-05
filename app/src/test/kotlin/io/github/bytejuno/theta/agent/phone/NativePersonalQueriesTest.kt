package io.github.bytejuno.theta.agent.phone

import android.content.ContentProviderOperation
import android.content.ContentProviderResult
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NativePersonalQueriesTest {
    private class FakeAccess(
        private val responses: List<(String) -> Cursor?>,
    ) : PhoneProviderAccess {
        override val callerPackage = "io.github.bytejuno.theta"
        private var index = 0
        val visited = mutableListOf<String>()

        override fun query(
            uri: Uri,
            columns: Array<String>,
            selection: String?,
            args: Array<String>?,
            sort: String?,
        ): Cursor? {
            visited += uri.toString()
            return responses.getOrNull(index++)?.invoke(uri.toString())
        }

        override fun insert(uri: Uri, values: ContentValues): Uri? = null
        override fun delete(uri: Uri, selection: String?, args: Array<String>?): Int = 0
        override fun call(uri: Uri, method: String, arg: String?, extras: Bundle): Bundle? = null
        override fun applyBatch(
            authority: String,
            operations: ArrayList<ContentProviderOperation>,
        ): Array<ContentProviderResult> = emptyArray()
    }

    @Test
    fun downloadsFallsBackWhenTheProviderRejectsTheColumnSet() {
        val access = FakeAccess(
            listOf(
                { throw IllegalArgumentException("Invalid column mime_type") },
                { _ ->
                    MatrixCursor(arrayOf("_id", "_display_name", "mime_type", "size", "date_modified", "relative_path"))
                        .apply { addRow(arrayOf("1", "report.pdf", "application/pdf", "12", "1700000000000", "Download/")) }
                },
            )
        )
        val result = NativePersonalQueries(access, 0).execute("search_downloads", JSONObject().put("limit", 5))
        assertTrue(result.getBoolean("ok"))
        assertEquals(2, access.visited.size)
        assertTrue(access.visited[1].contains("media/external/downloads"))
        val item = result.getJSONArray("items").getJSONObject(0)
        assertEquals("report.pdf", item.getString("_display_name"))
        assertEquals("Download/", item.getString("relative_path"))
    }

    @Test
    fun primarySourceStillWinsWhenItAnswers() {
        val access = FakeAccess(
            listOf(
                { _ -> MatrixCursor(arrayOf("_id", "title")).apply { addRow(arrayOf("7", "from-provider")) } },
            )
        )
        val result = NativePersonalQueries(access, 0).execute("search_downloads", JSONObject().put("limit", 5))
        assertTrue(result.getBoolean("ok"))
        assertEquals(1, access.visited.size)
        assertEquals("from-provider", result.getJSONArray("items").getJSONObject(0).getString("title"))
    }
}
