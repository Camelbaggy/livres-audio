package io.github.camelbaggy.livresaudio.core.data.db

import androidx.room.TypeConverter
import io.github.camelbaggy.livresaudio.core.data.identity.FieldMeta
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** JSON converters for the map columns (`fieldSources`, `providerIds`). Unknown keys are preserved. */
class Converters {
    @TypeConverter
    fun fieldSourcesToJson(value: Map<String, FieldMeta>): String = json.encodeToString(fieldSourcesSerializer, value)

    @TypeConverter
    fun fieldSourcesFromJson(value: String): Map<String, FieldMeta> =
        json.decodeFromString(fieldSourcesSerializer, value)

    @TypeConverter
    fun stringMapToJson(value: Map<String, String>): String = json.encodeToString(stringMapSerializer, value)

    @TypeConverter
    fun stringMapFromJson(value: String): Map<String, String> = json.decodeFromString(stringMapSerializer, value)
}

private val json = Json { ignoreUnknownKeys = true }
private val fieldSourcesSerializer: KSerializer<Map<String, FieldMeta>> =
    MapSerializer(String.serializer(), FieldMeta.serializer())
private val stringMapSerializer: KSerializer<Map<String, String>> =
    MapSerializer(String.serializer(), String.serializer())
