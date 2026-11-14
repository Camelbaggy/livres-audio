package io.github.camelbaggy.livresaudio.core.data.identity

import io.github.camelbaggy.livresaudio.core.model.FieldSource
import kotlinx.serialization.Serializable

/** Provenance and update time (UTC ms) of one information field, as stored in `fieldSources`. */
@Serializable
data class FieldMeta(val source: FieldSource, val updatedAt: Long)

/** A field value with its (possibly absent) provenance. */
data class MergedField<out T>(val value: T?, val meta: FieldMeta?)

/**
 * Single merge rule of CLAUDE.md §4.3: an incoming value replaces the current one only if its provenance
 * is strictly higher, or equal and strictly more recent. `USER` is never overwritten by a lower source.
 * A null incoming value never erases anything; a missing current value is filled by any incoming one.
 */
fun <T> mergeField(current: MergedField<T>, incomingValue: T?, incomingMeta: FieldMeta): MergedField<T> {
    if (incomingValue == null) return current
    val currentMeta = current.meta
    if (current.value == null || currentMeta == null) return MergedField(incomingValue, incomingMeta)
    val replaces = when {
        incomingMeta.source.priority > currentMeta.source.priority -> true
        incomingMeta.source.priority < currentMeta.source.priority -> false
        else -> incomingMeta.updatedAt > currentMeta.updatedAt
    }
    return if (replaces) MergedField(incomingValue, incomingMeta) else current
}
