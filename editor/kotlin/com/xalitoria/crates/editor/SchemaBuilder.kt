package com.xalitoria.crates.editor

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import mc.xalitoria.crates.paper.data.model.AnimationConfig
import mc.xalitoria.crates.paper.data.model.CrateConfig
import mc.xalitoria.crates.paper.data.model.Validation
import mc.xalitoria.crates.paper.data.model.Within
import mc.xalitoria.crates.paper.data.model.WithinRange

/**
 * Walks the kotlinx.serialization descriptors of [CrateConfig] and [AnimationConfig], pairing every field with its
 * `@Within`/`@WithinRange` bounds and KDoc / default literal from [SourceDocs].
 */
@OptIn(ExperimentalSerializationApi::class)
class SchemaBuilder(
    private val docs: SourceDocs,
    private val version: String,
) {
    private val types = sortedMapOf<String, TypeDef>()

    fun build(): SchemaFile {
        val crateRoot = describe(CrateConfig.serializer().descriptor)
        val animationRoot = describe(AnimationConfig.serializer().descriptor)
        return SchemaFile(
            version = version,
            crateRoot = crateRoot,
            animationRoot = animationRoot,
            builtinAnimations = Validation.BUILTIN_ANIMATIONS.sorted(),
            types = types,
        )
    }

    private fun describe(descriptor: SerialDescriptor): String = define(typeId(descriptor), descriptor)

    private fun typeId(descriptor: SerialDescriptor): String = descriptor.serialName.removeSuffix("?").substringAfterLast('.')

    private fun define(id: String, descriptor: SerialDescriptor): String {
        if (id in types) return id
        types[id] = TypeDef(fields = emptyList())
        val source = docs.find(descriptor.serialName.removeSuffix("?"))
        val fields = (0 until descriptor.elementsCount).map { index ->
            val key = descriptor.getElementName(index)
            val fieldDesc = descriptor.getElementDescriptor(index)
            val documented = source?.fields?.get(key)
            val annotations = descriptor.getElementAnnotations(index)
            val within = annotations.filterIsInstance<Within>().firstOrNull()
            val withinRange = annotations.filterIsInstance<WithinRange>().firstOrNull()
            val min = within?.min?.takeIf { it != Long.MIN_VALUE }?.let(::JsonPrimitive)
                ?: withinRange?.min?.takeIf { it.isFinite() }?.let(::JsonPrimitive)
            val max = within?.max?.takeIf { it != Long.MAX_VALUE }?.let(::JsonPrimitive)
                ?: withinRange?.max?.takeIf { it.isFinite() }?.let(::JsonPrimitive)
            FieldDef(
                key = key,
                type = typeOf(fieldDesc),
                required = !descriptor.isElementOptional(index),
                nullable = fieldDesc.isNullable,
                default = documented?.default?.let(::literal),
                min = min,
                max = max,
                doc = documented?.doc?.takeIf { it.isNotEmpty() },
            )
        }
        types[id] = TypeDef(doc = source?.doc?.takeIf { it.isNotEmpty() }, fields = fields)
        return id
    }

    private fun typeOf(descriptor: SerialDescriptor): FieldType = when {
        descriptor.kind is PrimitiveKind -> when (descriptor.kind) {
            PrimitiveKind.BOOLEAN -> FieldType.Switch
            PrimitiveKind.BYTE, PrimitiveKind.SHORT, PrimitiveKind.INT, PrimitiveKind.LONG -> FieldType.Whole
            PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE -> FieldType.Decimal
            else -> FieldType.Text
        }
        descriptor.kind == SerialKind.ENUM -> EnumType(descriptor.elementNames.toList())
        descriptor.kind == StructureKind.LIST -> ListType(typeOf(descriptor.getElementDescriptor(0)))
        descriptor.kind == StructureKind.MAP -> MapType(typeOf(descriptor.getElementDescriptor(1)))
        descriptor.kind == StructureKind.CLASS || descriptor.kind == StructureKind.OBJECT -> ObjectType(describe(descriptor))
        else -> FieldType.Anything
    }

    companion object {
        fun literal(expression: String): JsonElement? {
            val text = expression.trim()
            return when {
                text == "null" -> null
                text == "[]" || text == "emptyList()" -> JsonArray(emptyList())
                text == "emptyMap()" -> JsonObject(emptyMap())
                text == "true" -> JsonPrimitive(true)
                text == "false" -> JsonPrimitive(false)
                text.startsWith("\"") && text.endsWith("\"") && text.length >= 2 -> JsonPrimitive(text.substring(1, text.length - 1))
                Regex("""-?\d[\d_]*[Ll]?""").matches(text) -> JsonPrimitive(text.removeSuffix("L").removeSuffix("l").replace("_", "").toLong())
                Regex("""-?\d[\d_]*\.\d+[Ff]?""").matches(text) -> JsonPrimitive(text.removeSuffix("f").removeSuffix("F").replace("_", "").toDouble())
                Regex("""\w+\.([A-Z0-9_]+)""").matches(text) -> JsonPrimitive(text.substringAfterLast('.'))
                text.startsWith("[") && text.endsWith("]") -> {
                    val inner = text.substring(1, text.length - 1).trim()
                    if (inner.isEmpty()) JsonArray(emptyList())
                    else {
                        val items = inner.split(',').mapNotNull { literal(it) }
                        if (items.size == inner.split(',').size) JsonArray(items) else null
                    }
                }
                else -> null
            }
        }
    }
}
