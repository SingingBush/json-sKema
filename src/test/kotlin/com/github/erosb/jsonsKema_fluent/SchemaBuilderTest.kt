package com.github.erosb.jsonsKema_fluent

import com.github.erosb.jsonsKema.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.net.URI

class SchemaBuilderTest {
    @Test
    fun test() {
        val schema = SchemaBuilder
                .typeString()
                .minLength(2)
                .maxLength(3)
                .build()

        val failure = Validator.forSchema(schema).validate(JsonParser("\"\"")())!!

        assertThat(failure.message).isEqualTo("actual string length 0 is lower than minLength 2")
        assertThat(failure.schema.location.lineNumber).isEqualTo(14)
        assertThat(failure.schema.location.documentSource).isEqualTo(URI("classpath://com.github.erosb.jsonsKema_fluent.SchemaBuilderTest"))
        assertThat(failure.schema.location.pointer).isEqualTo(JsonPointer("minLength"))
        SourceLocation(
            lineNumber = 13,
            position = 0,
            documentSource = URI("classpath://com.github.erosb.jsonsKema_fluent.SchemaBuilderTest"),
            pointer = JsonPointer(listOf("minLength")),
        )
    }

    @Test
    fun `object properties`() {
        val schema = SchemaBuilder
                .typeObject()
                .property(
                    "propA",
                    SchemaBuilder
                        .typeString()
                        .minLength(4),
                ).property("arrayProp", SchemaBuilder.typeArray())
                .build()

        val failure =
            Validator.forSchema(schema).validate(
                JsonParser(
                    """
                    {
                      "propA": "bad"
                    }
                    """.trimIndent(),
                )(),
            )!!

        assertThat(failure.message).isEqualTo("actual string length 3 is lower than minLength 4")
        assertThat(failure.schema.location.lineNumber).isGreaterThan(30)
        assertThat(failure.schema.location.documentSource).isEqualTo(URI("classpath://com.github.erosb.jsonsKema_fluent.SchemaBuilderTest"))
        assertThat(failure.schema.location.pointer).isEqualTo(JsonPointer("properties", "propA", "minLength"))

        val typeFailure =
            Validator.forSchema(schema).validate(
                JsonParser(
                    """
                    {
                        "arrayProp": {}
                    }
                    """.trimIndent(),
                )(),
            )!!

        assertThat(typeFailure.schema.location.pointer).isEqualTo(JsonPointer("properties", "arrayProp", "type"))
    }

    @Test
    fun `regex patterns`() {
        val subject = SchemaBuilder.typeObject()
            .property("propA", SchemaBuilder.typeString()
                .pattern("\\d{2}.*"))
            .property("propB", SchemaBuilder.typeObject().patternProperties(mapOf(
                "[A-Z]{2}" to SchemaBuilder.typeString()
            )))
            .build()

        val actual = Validator.forSchema(subject).validate(JsonParser("""
            {
                "propA": "1asd",
                "propB": {
                    "HU": 0
                }
            }
        """.trimIndent())())!!

        println(actual)

        actual.causes.find { it.message.contains("instance value did not match pattern \\d{2}.*") }!!
        actual.causes.find { it.message.contains("expected type: string, actual: integer") }!!
    }

    @Test
    fun `array props`() {
        val schema = SchemaBuilder
                .typeArray()
                .minItems(2)
                .maxItems(5)
                .items(SchemaBuilder.typeObject()
                        .property("propA", SchemaBuilder.typeString()),
                ).contains(SchemaBuilder.typeObject()
                        .property(
                            "containedProp", SchemaBuilder.typeArray()
                                .items(SchemaBuilder.typeNumber())
                        ),
                ).minContains(2, SchemaBuilder.typeObject())
                .maxContains(5, SchemaBuilder.typeObject())
                .uniqueItems()
                .build()

        val minItemsLine =
            schema.subschemas()
                .find { it is MinItemsSchema }!!
                .location.lineNumber
        val maxItemsLine =
            schema.subschemas()
                .find { it is MaxItemsSchema }!!
                .location.lineNumber
        assertThat(maxItemsLine).isEqualTo(minItemsLine + 1)

        val itemsSchema = schema.subschemas().find { it is ItemsSchema }!! as ItemsSchema
        assertThat(itemsSchema.location.pointer).isEqualTo(JsonPointer("items"))
        val itemsSchemaLine = itemsSchema.location.lineNumber
        assertThat(itemsSchemaLine).isEqualTo(maxItemsLine + 1)
        assertThat((itemsSchema.itemsSchema as CompositeSchema)
                .propertySchemas["propA"]!!
                .subschemas()
                .find { it is TypeSchema }!!
                .location.pointer
                .toString(),
        ).isEqualTo("#/items/properties/propA/type")

        val containsLine = schema.subschemas()
                .find { it is ContainsSchema }!!
                .location.lineNumber
        assertThat(containsLine).isEqualTo(itemsSchemaLine + 2)
    }

    @Test
    fun moreTypes() {
        val schema = SchemaBuilder.typeObject()
            .property("nullProp", SchemaBuilder.typeNull())
            .property("boolProp", SchemaBuilder.typeBoolean())
            .property("typeInteger", SchemaBuilder.typeInteger())
            .build()

        val expected = CompositeSchema(propertySchemas = mapOf(
            "nullProp" to CompositeSchema(subschemas = setOf(
                TypeSchema(JsonString("null"), UnknownSource)
            )),
            "boolProp" to CompositeSchema(subschemas = setOf(
                TypeSchema(JsonString("boolean"), UnknownSource)
            )),
            "typeInteger" to CompositeSchema(subschemas = setOf(
                TypeSchema(JsonString("integer"), UnknownSource)
            ))
        ), subschemas = setOf(TypeSchema(JsonString("object"), UnknownSource))
        )
        assertBuiltSchema(schema, expected)
    }

    @Test
    fun moreObjectProps() {
        val schema = SchemaBuilder.typeObject()
            .minProperties(2)
            .maxProperties(3)
            .propertyNames(SchemaBuilder.typeString().minLength(3))
            .required("prop1", "prop2")
            .dependentRequired(mapOf(
                "prop3" to listOf("prop4", "prop5")
            ))
            .readOnly(true)
            .writeOnly(true)
            .build()

        val expected = CompositeSchema(subschemas = setOf(
            TypeSchema(JsonString("object"), UnknownSource),
            MinPropertiesSchema(2, UnknownSource),
            MaxPropertiesSchema(3, UnknownSource),
            PropertyNamesSchema(SchemaBuilder.typeString().minLength(3).build(), UnknownSource),
            RequiredSchema(listOf("prop1", "prop2"), UnknownSource),
            DependentRequiredSchema(mapOf(
                "prop3" to listOf("prop4", "prop5")
            ), UnknownSource),
            ReadOnlySchema(UnknownSource),
            WriteOnlySchema(UnknownSource)
        ))

        assertBuiltSchema(schema, expected)
    }

    private fun assertBuiltSchema(actual: Schema, expected: CompositeSchema) {
        assertThat(actual).usingRecursiveComparison()
            .ignoringFieldsOfTypes(SourceLocation::class.java)
            .isEqualTo(expected)
    }

    @Test
    fun unevaluatedProperties() {
        val schema = SchemaBuilder.empty()
            .unevaluatedProperties(SchemaBuilder.falseSchema())
            .build()

        val actual = Validator.forSchema(schema).validate(JsonParser("""
            {
                "propA": "1asd",
            }
        """.trimIndent())())!!

        println(actual)
        assertThat(actual)
            .hasFieldOrPropertyWithValue("message", "object properties propA failed to validate against \"unevaluatedProperties\" subschema")
            .hasFieldOrPropertyWithValue("keyword", Keyword.UNEVALUATED_PROPERTIES)
            .matches {fail -> fail.schema.location.pointer.toString() == "#/unevaluatedProperties" }
    }

    @Test
    fun unevaluatedItems() {
        val schema = SchemaBuilder.typeArray()
            .unevaluatedItems(SchemaBuilder.falseSchema())
            .build()

        val actual = Validator.forSchema(schema).validate(JsonParser("""
            [1]
        """.trimIndent())())!!

        println(actual)
        assertThat(actual)
            .hasFieldOrPropertyWithValue("message", "array items 0 failed to validate against \"unevaluatedItems\" subschema")
            .hasFieldOrPropertyWithValue("keyword", Keyword.UNEVALUATED_ITEMS)
            .matches {fail -> fail.schema.location.pointer.toString() == "#/unevaluatedItems" }
    }

    @Test
    fun ifThenElse() {
        val schema = SchemaBuilder.ifSchema(SchemaBuilder.typeString())
            .thenSchema(SchemaBuilder.empty().minLength(3))
            .elseSchema(SchemaBuilder.typeInteger().minimum(100))
            .build()

        val expected = CompositeSchema(subschemas = setOf(
            IfThenElseSchema(
                CompositeSchema(subschemas = setOf(TypeSchema(JsonString("string"), UnknownSource))),
                CompositeSchema(subschemas = setOf(
                    MinLengthSchema(3, UnknownSource)
                )),
                CompositeSchema(subschemas = setOf(
                    TypeSchema(JsonString("integer"), UnknownSource),
                    MinimumSchema(100, UnknownSource)
                )), UnknownSource
                )
        ))

        assertBuiltSchema(schema, expected)
    }

    @Test
    fun onlyIfThen() {
        val schema = SchemaBuilder.ifSchema(SchemaBuilder.typeString())
            .thenSchema(SchemaBuilder.empty().minLength(5))
            .build()

        val actual = Validator.forSchema(schema).validate(JsonParser("""
            "xx"
        """.trimIndent())())!!

        assertThat(actual.message).isEqualTo("actual string length 2 is lower than minLength 5")
        assertThat(actual.schema.location.pointer.toString()).isEqualTo("#/then/minLength")
    }

}
