/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.indexmanagement.snapshotmanagement.resthandler

import org.opensearch.client.Response
import org.opensearch.client.ResponseException
import org.opensearch.common.xcontent.XContentType
import org.opensearch.indexmanagement.IndexManagementPlugin.Companion.INDEX_MANAGEMENT_INDEX
import org.opensearch.indexmanagement.IndexManagementPlugin.Companion.SM_POLICIES_URI
import org.opensearch.indexmanagement.indexstatemanagement.util.XCONTENT_WITHOUT_TYPE_AND_USER
import org.opensearch.indexmanagement.makeRequest
import org.opensearch.indexmanagement.snapshotmanagement.SnapshotManagementRestTestCase
import org.opensearch.indexmanagement.snapshotmanagement.model.SMPolicy
import org.opensearch.indexmanagement.snapshotmanagement.model.SMPolicy.Companion.SM_TYPE
import org.opensearch.indexmanagement.snapshotmanagement.randomSMPolicy
import org.opensearch.indexmanagement.snapshotmanagement.settings.SnapshotManagementSettings
import org.opensearch.indexmanagement.snapshotmanagement.toMap
import org.opensearch.indexmanagement.util.IndexUtils
import org.opensearch.indexmanagement.util.NO_ID
import org.opensearch.indexmanagement.util._ID
import org.opensearch.indexmanagement.util._SEQ_NO
import org.opensearch.rest.RestStatus
import java.time.Instant

class RestIndexSnapshotManagementIT : SnapshotManagementRestTestCase() {

    @Suppress("UNCHECKED_CAST")
    fun `test creating a snapshot management policy`() {
        var smPolicy = randomSMPolicy()
        val response = client().makeRequest("POST", "$SM_POLICIES_URI/${smPolicy.policyName}", emptyMap(), smPolicy.toHttpEntity())
        assertEquals("Create SM policy failed", RestStatus.CREATED, response.restStatus())
        val responseBody = response.asMap()
        val createdId = responseBody["_id"] as String
        assertNotEquals("Response is missing Id", NO_ID, createdId)
        assertEquals("Not same id", smPolicy.id, createdId)
        assertEquals("Incorrect Location header", "$SM_POLICIES_URI/${smPolicy.policyName}", response.getHeader("Location"))
        val responseSMPolicy = responseBody[SM_TYPE] as Map<String, Any>
        // During indexing, we update these two fields so we need to override them here before the equality check
        smPolicy = smPolicy.copy(
            jobLastUpdateTime = Instant.ofEpochMilli(responseSMPolicy[SMPolicy.LAST_UPDATED_TIME_FIELD] as Long),
            schemaVersion = IndexUtils.indexManagementConfigSchemaVersion
        )
        assertEquals("Created and returned snapshot management policies differ", smPolicy.toMap(XCONTENT_WITHOUT_TYPE_AND_USER), responseSMPolicy)
    }

    @Suppress("UNCHECKED_CAST")
    fun `test updating a snapshot management policy with correct seq_no and primary_term`() {
        val smPolicy = createSMPolicy(randomSMPolicy())
        val updateResponse = client().makeRequest(
            "PUT",
            "$SM_POLICIES_URI/${smPolicy.policyName}?refresh=true&if_seq_no=${smPolicy.seqNo}&if_primary_term=${smPolicy.primaryTerm}",
            emptyMap(), smPolicy.toHttpEntity()
        )

        assertEquals("Update snapshot management policy failed", RestStatus.OK, updateResponse.restStatus())
        val responseBody = updateResponse.asMap()
        val updatedId = responseBody[_ID] as String
        val updatedSeqNo = (responseBody[_SEQ_NO] as Int).toLong()
        val updatedSMPolicy = responseBody["sm_policy"] as Map<String, Any>
        val lastUpdatedTime = updatedSMPolicy["last_updated_time"]
        assertNotEquals("response is missing Id", NO_ID, updatedId)
        assertEquals("not same id", smPolicy.id, updatedId)
        assertTrue("incorrect seqNo", smPolicy.seqNo < updatedSeqNo)
        assertEquals("Incorrect Location header", "$SM_POLICIES_URI/${smPolicy.policyName}", updateResponse.getHeader("Location"))
        assertNotEquals("last_updated_time should be updated.", smPolicy.lastUpdateTime, lastUpdatedTime)
    }

    fun `test updating a snapshot management policy with incorrect seq_no and primary_term`() {
        val smPolicy = createSMPolicy(randomSMPolicy())
        try {
            client().makeRequest(
                "PUT",
                "$SM_POLICIES_URI/${smPolicy.policyName}?refresh=true&if_seq_no=10251989&if_primary_term=2342",
                emptyMap(), smPolicy.toHttpEntity()
            )
            fail("expected 409 ResponseException")
        } catch (e: ResponseException) {
            assertEquals(RestStatus.CONFLICT, e.response.restStatus())
        }
        try {
            client().makeRequest(
                "PUT",
                "$SM_POLICIES_URI/${smPolicy.policyName}?refresh=true",
                emptyMap(), smPolicy.toHttpEntity()
            )
            fail("expected exception")
        } catch (e: ResponseException) {
            assertEquals(RestStatus.BAD_REQUEST, e.response.restStatus())
        }
    }

    fun `test updating a nonexistent snapshot management policy`() {
        val smPolicy = randomSMPolicy()
        try {
            client().makeRequest(
                "PUT",
                "$SM_POLICIES_URI/${smPolicy.policyName}?refresh=true&if_seq_no=10251989&if_primary_term=2342",
                emptyMap(), smPolicy.toHttpEntity()
            )
            fail("expected exception")
        } catch (e: ResponseException) {
            assertEquals(RestStatus.CONFLICT, e.response.restStatus())
        }
    }

    @Throws(Exception::class)
    fun `test creating sm policy with no name fails`() {
        try {
            val smPolicy = randomSMPolicy()
            client().makeRequest("POST", SM_POLICIES_URI, emptyMap(), smPolicy.toHttpEntity())
            fail("Expected METHOD_NOT_ALLOWED response")
        } catch (e: ResponseException) {
            assertEquals("Unexpected status", RestStatus.METHOD_NOT_ALLOWED, e.response.restStatus())
        }
    }

    @Throws(Exception::class)
    fun `test creating sm policy with PUT fails`() {
        try {
            val smPolicy = randomSMPolicy()
            client().makeRequest("PUT", SM_POLICIES_URI, emptyMap(), smPolicy.toHttpEntity())
            fail("Expected METHOD_NOT_ALLOWED response")
        } catch (e: ResponseException) {
            assertEquals("Unexpected status", RestStatus.METHOD_NOT_ALLOWED, e.response.restStatus())
        }
    }

    @Throws(Exception::class)
    @Suppress("UNCHECKED_CAST")
    fun `test mappings after sm policy creation`() {
        deleteIndex(INDEX_MANAGEMENT_INDEX)
        createSMPolicy(randomSMPolicy())

        val response = client().makeRequest("GET", "/$INDEX_MANAGEMENT_INDEX/_mapping")
        val parserMap = createParser(XContentType.JSON.xContent(), response.entity.content).map() as Map<String, Map<String, Any>>
        val mappingsMap = parserMap[INDEX_MANAGEMENT_INDEX]!!["mappings"] as Map<String, Any>
        val expected = createParser(
            XContentType.JSON.xContent(),
            javaClass.classLoader.getResource("mappings/opendistro-ism-config.json").readText()
        )
        val expectedMap = expected.map()

        assertEquals("Mappings are different", expectedMap, mappingsMap)
    }

    @Suppress("UNCHECKED_CAST")
    fun `test snapshot management policy create restrictions`() {
        val smPolicy = randomSMPolicy()

        // repository block
        val toBeBlockedRepositoryConfig = smPolicy.snapshotConfig.toMutableMap()
        toBeBlockedRepositoryConfig["repository"] = "cs-automated"
        val smPolicyRepositoryBlock = smPolicy.copy(
            snapshotConfig = toBeBlockedRepositoryConfig
        )
        try {
            client().makeRequest(
                "POST", "$SM_POLICIES_URI/${smPolicyRepositoryBlock.policyName}", emptyMap(),
                smPolicyRepositoryBlock.toHttpEntity()
            )
            fail("expected 400 ResponseException")
        } catch (e: ResponseException) {
            assertEquals(RestStatus.BAD_REQUEST, e.response.restStatus())
        }

        // deletion condition not set
        var smPolicyDeleteCondition = smPolicy.copy(
            deletion = null
        )
        var response = client().makeRequest("POST", "$SM_POLICIES_URI/${smPolicyDeleteCondition.policyName}", emptyMap(), smPolicyDeleteCondition.toHttpEntity())
        assertMaxCountInResponse(response, SnapshotManagementSettings.defaultMaxSnapshotsPerPolicy)
        client().makeRequest("DELETE", "$SM_POLICIES_URI/${smPolicyDeleteCondition.policyName}")

        // deletion condition set but max is beyond
        smPolicyDeleteCondition = smPolicy.copy(
            deletion = smPolicy.deletion!!.copy(
                condition = smPolicy.deletion!!.condition.copy(
                    maxCount = 500
                )
            )
        )
        response = client().makeRequest("POST", "$SM_POLICIES_URI/${smPolicyDeleteCondition.policyName}", emptyMap(), smPolicyDeleteCondition.toHttpEntity())
        assertMaxCountInResponse(response, SnapshotManagementSettings.defaultMaxSnapshotsPerPolicy)

        // too many policies for one repository
        val smPolicy2 = randomSMPolicy()
        try {
            client().makeRequest(
                "POST", "$SM_POLICIES_URI/${smPolicy2.policyName}", emptyMap(),
                smPolicy2.toHttpEntity()
            )
            fail("expected 400 ResponseException")
        } catch (e: ResponseException) {
            assertEquals(RestStatus.BAD_REQUEST, e.response.restStatus())
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun `test snapshot management policy update restrictions`() {
        val smPolicy = randomSMPolicy()
        client().makeRequest("POST", "$SM_POLICIES_URI/${smPolicy.policyName}", emptyMap(), smPolicy.toHttpEntity())

        // create another policy with different repo
        var smPolicy2 = randomSMPolicy(repository = "repo2")
        smPolicy2 = createSMPolicy(smPolicy2)

        // update the policy repo to the same with first, this should be blocked
        val mutableSnapshotConfig = smPolicy2.snapshotConfig.toMutableMap()
        mutableSnapshotConfig["repository"] = "repo"
        smPolicy2 = smPolicy2.copy(
            snapshotConfig = mutableSnapshotConfig
        )
        try {
            client().makeRequest(
                "PUT",
                "$SM_POLICIES_URI/${smPolicy2.policyName}?refresh=true&if_seq_no=${smPolicy2.seqNo}&if_primary_term=${smPolicy2.primaryTerm}",
                emptyMap(), smPolicy2.toHttpEntity()
            )
            fail("expected 400 ResponseException")
        } catch (e: ResponseException) {
            assertEquals(RestStatus.BAD_REQUEST, e.response.restStatus())
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun assertMaxCountInResponse(response: Response, expected: Int) {
        val responseBody = response.asMap()
        val policyInResponse = responseBody["sm_policy"] as Map<String, Any>
        val deleteInResponse = policyInResponse["deletion"] as Map<String, Any>
        val deleteConditionResponse = deleteInResponse["condition"] as Map<String, Any>
        val maxCountInResponse = deleteConditionResponse["max_count"] as Int
        assertEquals(expected, maxCountInResponse)
    }
}
