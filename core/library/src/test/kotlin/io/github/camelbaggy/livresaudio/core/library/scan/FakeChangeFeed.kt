package io.github.camelbaggy.livresaudio.core.library.scan

import io.github.camelbaggy.livresaudio.core.drive.rest.DriveError
import io.github.camelbaggy.livresaudio.core.drive.rest.DriveResult
import io.github.camelbaggy.livresaudio.core.model.AccountId

/** Scriptable [ChangeFeed]: batches by token; an unknown token is reported as expired. */
class FakeChangeFeed : ChangeFeed {
    val events = mutableListOf<String>()
    var start: DriveResult<String> = DriveResult.Success("t0")
    val batches = mutableMapOf<String, DriveResult<ChangeBatch>>()
    val parents = mutableMapOf<String, List<String>>()
    var parentsFailure: DriveError? = null
    var parentCalls = 0

    override suspend fun startToken(accountId: AccountId): DriveResult<String> {
        events += "start"
        return start
    }

    override suspend fun changesSince(accountId: AccountId, token: String): DriveResult<ChangeBatch> {
        events += "changes:$token"
        return batches[token] ?: DriveResult.Failure(DriveError.InvalidChangeToken("expiré"))
    }

    override suspend fun parentsOf(accountId: AccountId, fileId: String): DriveResult<List<String>> {
        parentCalls++
        parentsFailure?.let { return DriveResult.Failure(it) }
        return DriveResult.Success(parents[fileId].orEmpty())
    }
}
