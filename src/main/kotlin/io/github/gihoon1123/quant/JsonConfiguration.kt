package io.github.gihoon1123.quant

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.core.JsonGenerator
import tools.jackson.databind.SerializationContext
import tools.jackson.databind.module.SimpleModule
import tools.jackson.databind.ser.std.StdSerializer

@Configuration
class JsonConfiguration {
    @Bean
    fun javascriptNumbers(): SimpleModule {
        val serializer =
            object : StdSerializer<Double>(Double::class.javaObjectType) {
                override fun serialize(
                    value: Double,
                    generator: JsonGenerator,
                    context: SerializationContext,
                ) {
                    if (value.isFinite()) generator.writeNumber(value) else generator.writeNull()
                }
            }
        return SimpleModule("javascript-number-compatibility").apply {
            addSerializer(Double::class.javaObjectType, serializer)
            addSerializer(Double::class.javaPrimitiveType, serializer)
        }
    }
}
