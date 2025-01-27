/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package aws.sdk.kotlin.codegen.customization

import software.amazon.smithy.aws.traits.protocols.RestJson1Trait
import software.amazon.smithy.aws.traits.protocols.RestXmlTrait
import software.amazon.smithy.kotlin.codegen.KotlinSettings
import software.amazon.smithy.kotlin.codegen.integration.KotlinIntegration
import software.amazon.smithy.kotlin.codegen.model.expectShape
import software.amazon.smithy.kotlin.codegen.model.findStreamingMember
import software.amazon.smithy.kotlin.codegen.utils.getOrNull
import software.amazon.smithy.model.Model
import software.amazon.smithy.model.knowledge.ServiceIndex
import software.amazon.smithy.model.shapes.OperationShape
import software.amazon.smithy.model.shapes.StructureShape
import software.amazon.smithy.model.transform.ModelTransformer
import java.util.logging.Logger

/**
 * Integration that pre-processes the model to REMOVE operations that use event streaming until it is supported
 * See: https://awslabs.github.io/smithy/1.0/spec/core/stream-traits.html#event-streams
 */
class RemoveEventStreamOperations : KotlinIntegration {
    override val order: Byte = -127
    private val logger = Logger.getLogger(javaClass.name)

    private val supportedProtocols = setOf(
        RestXmlTrait.ID,
        RestJson1Trait.ID,
    )
    override fun enabledForService(model: Model, settings: KotlinSettings): Boolean {
        val serviceIndex = ServiceIndex(model)
        val protocols = serviceIndex.getProtocols(settings.service)
            .values
            .map { it.toShapeId() }

        return protocols.any { it !in supportedProtocols }
    }

    override fun preprocessModel(model: Model, settings: KotlinSettings): Model =
        ModelTransformer.create().filterShapes(model) { parentShape ->
            if (parentShape !is OperationShape) {
                true
            } else {
                val ioShapes = listOfNotNull(parentShape.output.getOrNull(), parentShape.input.getOrNull()).map { model.expectShape<StructureShape>(it) }
                val hasEventStream = ioShapes.any { ioShape ->
                    val streamingMember = ioShape.findStreamingMember(model)
                    val target = streamingMember?.let { model.expectShape(it.target) }
                    target?.isUnionShape ?: false
                }
                // If a streaming member has a union trait, it is an event stream. Event Streams are not currently supported
                // by the SDK, so if we generate this API it won't work.
                (!hasEventStream).also {
                    if (!it) {
                        logger.warning("Removed $parentShape from model because it targets an event stream")
                    }
                }
            }
        }
}
