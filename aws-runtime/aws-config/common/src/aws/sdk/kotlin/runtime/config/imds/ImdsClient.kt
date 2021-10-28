/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0.
 */

package aws.sdk.kotlin.runtime.config.imds

import aws.sdk.kotlin.runtime.AwsServiceException
import aws.sdk.kotlin.runtime.ConfigurationException
import aws.sdk.kotlin.runtime.client.AwsClientOption
import aws.sdk.kotlin.runtime.endpoint.Endpoint
import aws.sdk.kotlin.runtime.http.ApiMetadata
import aws.sdk.kotlin.runtime.http.AwsUserAgentMetadata
import aws.sdk.kotlin.runtime.http.engine.crt.CrtHttpEngine
import aws.sdk.kotlin.runtime.http.middleware.ServiceEndpointResolver
import aws.sdk.kotlin.runtime.http.middleware.UserAgent
import aws.smithy.kotlin.runtime.client.ExecutionContext
import aws.smithy.kotlin.runtime.client.SdkClientOption
import aws.smithy.kotlin.runtime.client.SdkLogMode
import aws.smithy.kotlin.runtime.http.*
import aws.smithy.kotlin.runtime.http.engine.HttpClientEngine
import aws.smithy.kotlin.runtime.http.operation.*
import aws.smithy.kotlin.runtime.http.response.HttpResponse
import aws.smithy.kotlin.runtime.io.Closeable
import aws.smithy.kotlin.runtime.io.middleware.Phase
import aws.smithy.kotlin.runtime.logging.Logger
import aws.smithy.kotlin.runtime.time.Clock
import aws.smithy.kotlin.runtime.util.Platform
import aws.smithy.kotlin.runtime.util.PlatformProvider
import kotlin.time.Duration
import kotlin.time.ExperimentalTime

/**
 * Maximum time allowed by default (6 hours)
 */
internal const val DEFAULT_TOKEN_TTL_SECONDS: Int = 21_600
internal const val DEFAULT_MAX_RETRIES: UInt = 3u

private const val SERVICE = "imds"

/**
 * Represents a generic client that can fetch instance metadata.
 */
public interface InstanceMetadataProvider : Closeable {
    /**
     * Gets the specified instance metadata value by the given path.
     */
    public suspend fun get(path: String): String
}

/**
 * IMDSv2 Client
 *
 * This client supports fetching tokens, retrying failures, and token caching according to the specified TTL.
 *
 * NOTE: This client ONLY supports IMDSv2. It will not fallback to IMDSv1.
 * See [transitioning to IMDSv2](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/configuring-instance-metadata-service.html#instance-metadata-transition-to-version-2)
 * for more information.
 */
@OptIn(ExperimentalTime::class)
public class ImdsClient private constructor(builder: Builder) : InstanceMetadataProvider {
    public constructor() : this(Builder())

    private val logger = Logger.getLogger<ImdsClient>()

    private val maxRetries: UInt = builder.maxRetries
    private val endpointConfiguration: EndpointConfiguration = builder.endpointConfiguration
    private val tokenTtl: Duration = builder.tokenTtl
    private val clock: Clock = builder.clock
    private val platformProvider: PlatformProvider = builder.platformProvider
    private val sdkLogMode: SdkLogMode = builder.sdkLogMode
    private val httpClient: SdkHttpClient

    init {
        val engine = builder.engine ?: CrtHttpEngine {
            connectTimeout = Duration.seconds(1)
            socketReadTimeout = Duration.seconds(1)
        }

        httpClient = sdkHttpClient(engine)

        // validate the override at construction time
        if (endpointConfiguration is EndpointConfiguration.Custom) {
            val url = endpointConfiguration.endpoint.toUrl()
            try {
                Url.parse(url.toString())
            } catch (ex: Exception) {
                throw ConfigurationException("Invalid endpoint configuration: `$url` is not a valid URI", ex)
            }
        }
    }

    // cached middleware instances
    private val middleware: List<Feature> = listOf(
        ServiceEndpointResolver.create {
            serviceId = SERVICE
            resolver = ImdsEndpointResolver(platformProvider, endpointConfiguration)
        },
        UserAgent.create {
            staticMetadata = AwsUserAgentMetadata.fromEnvironment(ApiMetadata(SERVICE, "unknown"))
        },
        TokenMiddleware.create {
            httpClient = this@ImdsClient.httpClient
            ttl = tokenTtl
            clock = this@ImdsClient.clock
        }
    )

    public companion object {
        public operator fun invoke(block: Builder.() -> Unit): ImdsClient = ImdsClient(Builder().apply(block))
    }

    /**
     * Retrieve information from instance metadata service (IMDS).
     *
     * This method will combine [path] with the configured endpoint and return the response as a string.
     *
     * For more information about IMDSv2 methods and functionality, see
     * [Instance metadata and user data](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/ec2-instance-metadata.html)
     *
     * Example:
     *
     * ```kotlin
     * val client = EC2Metadata()
     * val amiId = client.get("/latest/meta-data/ami-id")
     * ```
     */
    public override suspend fun get(path: String): String {
        val op = SdkHttpOperation.build<Unit, String> {
            serializer = UnitSerializer
            deserializer = object : HttpDeserialize<String> {
                override suspend fun deserialize(context: ExecutionContext, response: HttpResponse): String {
                    if (response.status.isSuccess()) {
                        val payload = response.body.readAll() ?: throw EC2MetadataError(response.status.value, "no metadata payload")
                        return payload.decodeToString()
                    } else {
                        throw EC2MetadataError(response.status.value, "error retrieving instance metadata")
                    }
                }
            }
            context {
                operationName = path
                service = SERVICE
                // artifact of re-using ServiceEndpointResolver middleware
                set(AwsClientOption.Region, "not-used")
                set(SdkClientOption.LogMode, sdkLogMode)
            }
        }
        middleware.forEach { it.install(op) }
        op.execution.mutate.intercept(Phase.Order.Before) { req, next ->
            req.subject.url.path = path
            next.call(req)
        }

        // TODO - retries
        return op.roundTrip(httpClient, Unit)
    }

    override fun close() {
        httpClient.close()
    }

    public class Builder {
        /**
         * The maximum number of retries for fetching tokens and metadata
         */
        public var maxRetries: UInt = DEFAULT_MAX_RETRIES

        /**
         * The endpoint configuration to use when making requests
         */
        public var endpointConfiguration: EndpointConfiguration = EndpointConfiguration.Default

        /**
         * Override the time-to-live for the session token
         */
        public var tokenTtl: Duration = Duration.seconds(DEFAULT_TOKEN_TTL_SECONDS)

        /**
         * Configure the [SdkLogMode] used by the client
         */
        public var sdkLogMode: SdkLogMode = SdkLogMode.Default

        /**
         * The HTTP engine to use to make requests with. This is here to facilitate testing and can otherwise be ignored
         */
        internal var engine: HttpClientEngine? = null

        /**
         * The source of time for token refreshes. This is here to facilitate testing and can otherwise be ignored
         */
        internal var clock: Clock = Clock.System

        /**
         * The platform provider. This is here to facilitate testing and can otherwise be ignored
         */
        internal var platformProvider: PlatformProvider = Platform
    }
}

public sealed class EndpointConfiguration {
    /**
     * Detected from the execution environment
     */
    public object Default : EndpointConfiguration()

    /**
     * Override the endpoint to make requests to
     */
    public data class Custom(val endpoint: Endpoint) : EndpointConfiguration()

    /**
     * Override the [EndpointMode] to use
     */
    public data class ModeOverride(val mode: EndpointMode) : EndpointConfiguration()
}

public enum class EndpointMode(internal val defaultEndpoint: Endpoint) {
    /**
     * IPv4 mode. This is the default unless otherwise specified
     * e.g. `http://169.254.169.254'
     */
    IPv4(Endpoint("169.254.169.254", "http")),

    /**
     * IPv6 mode
     * e.g. `http://[fd00:ec2::254]`
     */
    IPv6(Endpoint("[fd00:ec2::254]", "http"));

    public companion object {
        public fun fromValue(value: String): EndpointMode = when (value.lowercase()) {
            "ipv4" -> IPv4
            "ipv6" -> IPv6
            else -> throw IllegalArgumentException("invalid EndpointMode: `$value`")
        }
    }
}

/**
 * Exception thrown when an error occurs retrieving metadata from IMDS
 *
 * @param statusCode The raw HTTP status code of the response
 * @param message The error message
 */
public class EC2MetadataError(public val statusCode: Int, message: String) : AwsServiceException(message)

private fun Endpoint.toUrl(): Url {
    val endpoint = this
    val protocol = Protocol.parse(endpoint.protocol)
    return Url(
        scheme = protocol,
        host = endpoint.hostname,
        port = endpoint.port ?: protocol.defaultPort,
    )
}
