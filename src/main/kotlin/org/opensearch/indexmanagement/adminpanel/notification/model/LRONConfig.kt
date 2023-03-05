/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.indexmanagement.adminpanel.notification.model

import org.opensearch.common.io.stream.StreamInput
import org.opensearch.common.io.stream.StreamOutput
import org.opensearch.common.io.stream.Writeable
import org.opensearch.core.xcontent.ToXContent
import org.opensearch.core.xcontent.ToXContentObject
import org.opensearch.core.xcontent.XContentBuilder
import org.opensearch.core.xcontent.XContentParser
import org.opensearch.common.xcontent.XContentParserUtils.ensureExpectedToken
import org.opensearch.commons.authuser.User
import org.opensearch.index.seqno.SequenceNumbers
import org.opensearch.indexmanagement.common.model.notification.Channel
import org.opensearch.indexmanagement.indexstatemanagement.util.WITH_TYPE
import org.opensearch.indexmanagement.indexstatemanagement.util.WITH_USER
import org.opensearch.indexmanagement.opensearchapi.optionalUserField
import org.opensearch.indexmanagement.util.NO_ID
import org.opensearch.script.Script
import java.io.IOException

data class LRONConfig(
    val enabled: Boolean,
    val taskID: String?,
    val actionName: String?,
    val channels: List<Channel>?,
    val user: User?,
    val successMessageTemplate: Script?,
    val failedMessageTemplate: Script?,
) : ToXContentObject, Writeable {

    init {
        if (enabled) {
            require(!channels.isNullOrEmpty()) { "Enabled LRONConfig must contain at least one channel" }
            // require(null != successMessageTemplate && null != failedMessageTemplate) { "Enabled LRONConfig must has message template" }
//            require(successMessageTemplate?.lang == MUSTACHE && failedMessageTemplate?.lang == MUSTACHE) {
//                "LRONConfig message template must be a mustache script"
//            }
        }
    }

    fun toXContent(builder: XContentBuilder): XContentBuilder {
        return toXContent(builder, ToXContent.EMPTY_PARAMS)
    }

    override fun toXContent(builder: XContentBuilder, params: ToXContent.Params): XContentBuilder {
        builder.startObject()
        if (params.paramAsBoolean(WITH_TYPE, true)) builder.startObject(LRON_CONFIG_FIELD)
        builder.field(ENABLED_FIELD, enabled)
        if (null != taskID) builder.field(TASK_ID_FIELD, taskID)
        if (null != actionName) builder.field(ACTION_NAME_FIELD, actionName)
        if (params.paramAsBoolean(WITH_USER, true)) builder.optionalUserField(USER_FIELD, user)
        if (enabled) {
            builder.startArray(CHANNELS_FIELD)
                .also { channels?.forEach { channel -> channel.toXContent(it, params) } }
                .endArray()
                .field(SUCCESS_MESSAGE_TEMPLATE_FIELD, successMessageTemplate)
                .field(FAILED_MESSAGE_TEMPLATE_FIELD, failedMessageTemplate)
        }
        if (params.paramAsBoolean(WITH_TYPE, true)) builder.endObject()
        return builder.endObject()
    }

    @Throws(IOException::class)
    constructor(sin: StreamInput) : this(
        enabled = sin.readBoolean(),
        taskID = sin.readOptionalString(),
        actionName = sin.readOptionalString(),
        channels = if (sin.readBoolean()) {
            sin.readList(::Channel)
        } else null,
        user = sin.readOptionalWriteable(::User),
        successMessageTemplate = sin.readOptionalWriteable(::Script),
        failedMessageTemplate = sin.readOptionalWriteable(::Script)
    )

    @Throws(IOException::class)
    override fun writeTo(out: StreamOutput) {
        out.writeBoolean(enabled)
        out.writeOptionalString(taskID)
        out.writeOptionalString(actionName)
        if (null != channels) {
            out.writeBoolean(true)
            out.writeList(channels)
        } else out.writeBoolean(false)
        out.writeOptionalWriteable(user)
        out.writeOptionalWriteable(successMessageTemplate)
        out.writeOptionalWriteable(failedMessageTemplate)
    }

    companion object {
        const val LRON_CONFIG_FIELD = "lron_config"
        const val ENABLED_FIELD = "enabled"
        const val TASK_ID_FIELD = "task_id"
        const val ACTION_NAME_FIELD = "action_name"
        const val CHANNELS_FIELD = "channels"
        const val USER_FIELD = "user"
        const val SUCCESS_MESSAGE_TEMPLATE_FIELD = "success_message_template"
        const val FAILED_MESSAGE_TEMPLATE_FIELD = "failed_message_template"

        const val MUSTACHE = "mustache"
        const val CHANNEL_TITLE = "Long Running Operation Notification"
        const val DEFAULT_ENABLED = true

        /* to fit with ISM XContentParser.parseWithType function */
        @JvmStatic
        @Throws(IOException::class)
        @Suppress("UNUSED_PARAMETER")
        fun parse(
            xcp: XContentParser,
            id: String = NO_ID,
            seqNo: Long = SequenceNumbers.UNASSIGNED_SEQ_NO,
            primaryTerm: Long = SequenceNumbers.UNASSIGNED_PRIMARY_TERM
        ): LRONConfig {
            return parse(xcp)
        }

        @JvmStatic
        @Throws(IOException::class)
        fun parse(xcp: XContentParser): LRONConfig {
            var enabled: Boolean = DEFAULT_ENABLED
            var taskID: String? = null
            var actionName: String? = null
            var channels: List<Channel>? = null
            var user: User? = null
            var successMessageTemplate: Script? = null
            var failedMessageTemplate: Script? = null

            ensureExpectedToken(XContentParser.Token.START_OBJECT, xcp.currentToken(), xcp)
            while (xcp.nextToken() != XContentParser.Token.END_OBJECT) {
                val fieldName = xcp.currentName()
                xcp.nextToken()

                when (fieldName) {
                    ENABLED_FIELD -> enabled = xcp.booleanValue()
                    TASK_ID_FIELD -> taskID = if (xcp.currentToken() == XContentParser.Token.VALUE_NULL) null else xcp.text()
                    ACTION_NAME_FIELD -> actionName = if (xcp.currentToken() == XContentParser.Token.VALUE_NULL) null else xcp.text()
                    CHANNELS_FIELD -> {
                        if (xcp.currentToken() != XContentParser.Token.VALUE_NULL) {
                            channels = mutableListOf()
                            ensureExpectedToken(XContentParser.Token.START_ARRAY, xcp.currentToken(), xcp)
                            while (xcp.nextToken() != XContentParser.Token.END_ARRAY) {
                                channels.add(Channel.parse(xcp))
                            }
                        }
                    }
                    USER_FIELD -> user = if (xcp.currentToken() == XContentParser.Token.VALUE_NULL) null else User.parse(xcp)
                    SUCCESS_MESSAGE_TEMPLATE_FIELD -> if (xcp.currentToken() != XContentParser.Token.VALUE_NULL) {
                        successMessageTemplate = Script.parse(xcp, Script.DEFAULT_TEMPLATE_LANG)
                    }
                    FAILED_MESSAGE_TEMPLATE_FIELD -> if (xcp.currentToken() != XContentParser.Token.VALUE_NULL) {
                        failedMessageTemplate = Script.parse(xcp, Script.DEFAULT_TEMPLATE_LANG)
                    }
                    else -> throw IllegalArgumentException("Invalid field: [$fieldName] found in LRONConfig.")
                }
            }

            return LRONConfig(
                enabled = enabled,
                taskID = taskID,
                actionName = actionName,
                channels = channels,
                user = user,
                successMessageTemplate = successMessageTemplate,
                failedMessageTemplate = failedMessageTemplate
            )
        }
    }
}
