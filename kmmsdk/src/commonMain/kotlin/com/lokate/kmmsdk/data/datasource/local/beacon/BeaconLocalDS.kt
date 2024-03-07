package com.lokate.kmmsdk.data.datasource.local.beacon

import com.lokate.kmmsdk.Database
import com.lokate.kmmsdk.data.datasource.DSResult
import com.lokate.kmmsdk.data.datasource.interfaces.beacon.BeaconDS
import com.lokate.kmmsdk.domain.model.beacon.ActiveBeacon
import com.lokate.kmmsdk.domain.model.beacon.BeaconRange
import com.lokate.kmmsdk.domain.model.beacon.Campaign
import com.lokate.kmmsdk.utils.extension.EMPTY_STRING

class BeaconLocalDS(
    database: Database,
) : BeaconDS {
    private val queries = database.beaconDatabaseQueries

    override suspend fun fetchBeacons(branchId: String): DSResult<List<ActiveBeacon>> {
        return try {
            queries.selectAllBeacons().executeAsList().let {
                DSResult.Success(it.map {
                    ActiveBeacon(
                        userId = it.uuid,
                        major = it.major.toString(),
                        minor = it.minor.toString(),
                        range = BeaconRange.fromInt(it.range.toInt()),
                        branchId = EMPTY_STRING,
                        radius = 0,
                        name = EMPTY_STRING,
                        campaign = Campaign(
                            EMPTY_STRING,
                            EMPTY_STRING,
                            EMPTY_STRING,
                            EMPTY_STRING,
                            EMPTY_STRING
                        ),
                        id = EMPTY_STRING
                    )
                }
                )
            }
        } catch (e: Exception) {
            DSResult.Error(e.message, e)
        }
    }

    suspend fun updateOrInsertBeacon(beacons: List<ActiveBeacon>): DSResult<Boolean> {
        return try {
            queries.transaction {
                beacons.forEach {
                    queries.insertBeacon(
                        uuid = it.userId,
                        major = it.major.toLong(),
                        minor = it.minor.toLong(),
                        range = it.range.ordinal.toLong()
                    )
                }
            }
            DSResult.Success(true)
        } catch (e: Exception) {
            e.printStackTrace()
            DSResult.Error(e.message, e)
        }
    }

    suspend fun removeBeacons(): DSResult<Boolean> {
        return try {
            queries.transaction {
                queries.removeAllBeacons()
            }
            DSResult.Success(true)
        } catch (e: Exception) {
            e.printStackTrace()
            DSResult.Error(e.message, e)
        }
    }
}
