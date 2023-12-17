package com.lokate.kmmsdk.domain.repository

import com.lokate.kmmsdk.domain.model.beacon.LokateBeacon
import com.lokate.kmmsdk.domain.model.repository.RepositoryResult

interface BeaconRepository {
    suspend fun fetchBeacons(): RepositoryResult<List<LokateBeacon>>
    suspend fun deleteBeacons(): RepositoryResult<Boolean>
    suspend fun addBeacons(beacons:List<LokateBeacon>): RepositoryResult<Boolean>
    suspend fun getBeaconsFromDb(): RepositoryResult<List<LokateBeacon>>
}