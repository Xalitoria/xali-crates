package com.xalitoria.crates.editor

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * Shape of `crates/editor/web/schema.json`. Generated from the Kotlin serializer descriptors and KDoc comments of
 * `CrateConfig` and `AnimationConfig` so the web editor cannot drift from the plugin schema.
 */
@Serializable
data class SchemaFile(
    val version: String,
    /** Root type name for `crates/<id>.yml`. */
    val crateRoot: String,
    /** Root type name for `animations/<id>.yml`. */
    val animationRoot: String,
    /** Built-in animation identifiers accepted in `opening.animation`. */
    val builtinAnimations: List<String>,
    /** Every serializable class reachable from [crateRoot] or [animationRoot]. */
    val types: Map<String, TypeDef>,
)

@Serializable
data class TypeDef(
    val doc: String? = null,
    val fields: List<FieldDef>,
)

@Serializable
data class FieldDef(
    /** YAML key name (kebab-case / `@SerialName`). */
    val key: String,
    val type: FieldType,
    val required: Boolean,
    val nullable: Boolean = false,
    /** Default value as JSON when written as a literal in the Kotlin source. */
    val default: JsonElement? = null,
    /** Inclusive lower bound from `@Within` or `@WithinRange`. */
    val min: JsonPrimitive? = null,
    /** Inclusive upper bound from `@Within` or `@WithinRange`. */
    val max: JsonPrimitive? = null,
    val doc: String? = null,
)

@Serializable
sealed interface FieldType {
    @Serializable @SerialName("string") data object Text : FieldType
    @Serializable @SerialName("int") data object Whole : FieldType
    @Serializable @SerialName("number") data object Decimal : FieldType
    @Serializable @SerialName("boolean") data object Switch : FieldType
    @Serializable @SerialName("any") data object Anything : FieldType
}

@Serializable @SerialName("enum") data class EnumType(val values: List<String>) : FieldType

@Serializable @SerialName("list") data class ListType(val of: FieldType) : FieldType

@Serializable @SerialName("map") data class MapType(val of: FieldType) : FieldType

@Serializable @SerialName("object") data class ObjectType(val type: String) : FieldType

val schemaJson = Json {
    classDiscriminator = "kind"
    prettyPrint = true
    prettyPrintIndent = "  "
    explicitNulls = false
}
