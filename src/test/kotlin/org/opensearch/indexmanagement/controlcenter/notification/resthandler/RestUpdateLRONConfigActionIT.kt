/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.indexmanagement.controlcenter.notification.resthandler

import org.junit.Assert
import org.opensearch.client.ResponseException
import org.opensearch.indexmanagement.controlcenter.notification.getResourceURI
import org.opensearch.indexmanagement.controlcenter.notification.model.LRONConfig
import org.opensearch.indexmanagement.controlcenter.notification.nodeIdsInRestIT
import org.opensearch.indexmanagement.controlcenter.notification.randomLRONCondition
import org.opensearch.indexmanagement.controlcenter.notification.randomLRONConfig
import org.opensearch.indexmanagement.controlcenter.notification.randomTaskId
import org.opensearch.indexmanagement.controlcenter.notification.util.getDocID
import org.opensearch.indexmanagement.indexstatemanagement.randomChannel
import org.opensearch.indexmanagement.makeRequest
import org.opensearch.indexmanagement.opensearchapi.convertToMap
import org.opensearch.rest.RestStatus

@Suppress("UNCHECKED_CAST")
class RestUpdateLRONConfigActionIT : LRONConfigRestTestCase() {
    fun `test update LRONConfig`() {
        val lronConfig = randomLRONConfig(taskId = randomTaskId(nodeId = nodeIdsInRestIT.random()))
        createLRONConfig(lronConfig)

        val newLRONConfig = LRONConfig(
            lronCondition = randomLRONCondition(),
            taskId = lronConfig.taskId,
            actionName = lronConfig.actionName,
            channels = List(10) { randomChannel() },
            user = null,
            priority = null
        )

        val response = client().makeRequest(
            "PUT",
            getResourceURI(lronConfig.taskId, lronConfig.actionName),
            emptyMap(),
            newLRONConfig.toHttpEntity()
        )

        assertEquals("update LRONConfig failed", RestStatus.OK, response.restStatus())
        val responseBody = response.asMap()
        val updatedId = responseBody["_id"] as String
        Assert.assertEquals("not same doc id", getDocID(lronConfig.taskId, lronConfig.actionName), updatedId)
        val newLRONConfigMap = newLRONConfig.convertToMap()[LRONConfig.LRON_CONFIG_FIELD] as Map<String, Any>
        Assert.assertEquals(
            "not same LRONConfig",
            newLRONConfigMap.filterKeys { it != LRONConfig.USER_FIELD && it != LRONConfig.PRIORITY_FIELD },
            responseBody["lron_config"] as Map<String, Any>
        )
    }

    fun `test update nonexist LRONConfig fails`() {
        try {
            val lronConfig = randomLRONConfig(taskId = randomTaskId(nodeId = nodeIdsInRestIT.random()))
            client().makeRequest(
                "PUT",
                getResourceURI(lronConfig.taskId, lronConfig.actionName),
                emptyMap(),
                lronConfig.toHttpEntity()
            )
            fail("Expected 404 NOT_FOUND")
        } catch (e: ResponseException) {
            logger.info(e.response.asMap())
            assertEquals("Unexpected status", RestStatus.NOT_FOUND, e.response.restStatus())
        }
    }
}
