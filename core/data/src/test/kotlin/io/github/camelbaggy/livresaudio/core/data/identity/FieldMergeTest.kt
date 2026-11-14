package io.github.camelbaggy.livresaudio.core.data.identity

import io.github.camelbaggy.livresaudio.core.model.FieldSource
import kotlin.test.Test
import kotlin.test.assertEquals

class FieldMergeTest {
    private fun meta(source: FieldSource, at: Long) = FieldMeta(source, at)

    private data class Case(
        val name: String,
        val current: MergedField<String>,
        val incomingValue: String?,
        val incoming: FieldMeta,
        val expectedValue: String?
    )

    @Test
    fun mergeRuleTable() {
        val cases = listOf(
            Case("empty current is filled", MergedField(null, null), "new", meta(FieldSource.FOLDER, 1), "new"),
            Case(
                "higher replaces lower",
                MergedField("a", meta(FieldSource.FOLDER, 9)),
                "b",
                meta(FieldSource.EMBEDDED, 1),
                "b"
            ),
            Case(
                "lower never replaces higher",
                MergedField("a", meta(FieldSource.EMBEDDED, 1)),
                "b",
                meta(FieldSource.FOLDER, 9),
                "a"
            ),
            Case(
                "USER is never overwritten by FOLDER",
                MergedField("a", meta(FieldSource.USER, 1)),
                "b",
                meta(FieldSource.FOLDER, 99),
                "a"
            ),
            Case(
                "USER is never overwritten by EMBEDDED",
                MergedField("a", meta(FieldSource.USER, 1)),
                "b",
                meta(FieldSource.EMBEDDED, 99),
                "a"
            ),
            Case(
                "equal source, newer wins",
                MergedField("a", meta(FieldSource.FOLDER, 1)),
                "b",
                meta(FieldSource.FOLDER, 2),
                "b"
            ),
            Case(
                "equal source, older loses",
                MergedField("a", meta(FieldSource.FOLDER, 2)),
                "b",
                meta(FieldSource.FOLDER, 1),
                "a"
            ),
            Case(
                "equal source, same time keeps",
                MergedField("a", meta(FieldSource.FOLDER, 2)),
                "b",
                meta(FieldSource.FOLDER, 2),
                "a"
            ),
            Case(
                "ONLINE above FOLDER",
                MergedField("a", meta(FieldSource.FOLDER, 5)),
                "b",
                meta(FieldSource.ONLINE, 1),
                "b"
            ),
            Case(
                "EMBEDDED above ONLINE",
                MergedField("a", meta(FieldSource.ONLINE, 5)),
                "b",
                meta(FieldSource.EMBEDDED, 1),
                "b"
            ),
            Case(
                "null incoming never erases",
                MergedField("a", meta(FieldSource.FOLDER, 1)),
                null,
                meta(FieldSource.USER, 9),
                "a"
            )
        )
        for (c in cases) {
            assertEquals(c.expectedValue, mergeField(c.current, c.incomingValue, c.incoming).value, c.name)
        }
    }

    @Test
    fun metaFollowsTheWinningValue() {
        val result = mergeField(MergedField("a", meta(FieldSource.FOLDER, 1)), "b", meta(FieldSource.USER, 7))
        assertEquals(meta(FieldSource.USER, 7), result.meta)
    }
}
