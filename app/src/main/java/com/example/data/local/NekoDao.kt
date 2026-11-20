package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ProfileDao {
    @Query("SELECT * FROM proxy_profiles ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<ProfileEntity>>

    @Query("SELECT * FROM proxy_profiles WHERE groupId = :groupId ORDER BY createdAt DESC")
    fun observeByGroup(groupId: Long): Flow<List<ProfileEntity>>

    @Query("SELECT * FROM proxy_profiles WHERE id = :id")
    suspend fun getById(id: Long): ProfileEntity?

    @Query("SELECT * FROM proxy_profiles ORDER BY createdAt DESC")
    suspend fun getAllSync(): List<ProfileEntity>

    @Query("SELECT * FROM proxy_profiles WHERE groupId = :groupId")
    suspend fun getByGroupSync(groupId: Long): List<ProfileEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: ProfileEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<ProfileEntity>): List<Long>

    @Update
    suspend fun update(entity: ProfileEntity)

    @Query("UPDATE proxy_profiles SET latencyMs = :latencyMs, tlsLatencyMs = :tlsLatencyMs, lastTestedAt = :testedAt WHERE id = :id")
    suspend fun updateLatency(id: Long, latencyMs: Int?, tlsLatencyMs: Int?, testedAt: Long)

    @Query("UPDATE proxy_profiles SET chainProxyId = 0 WHERE chainProxyId = :targetId")
    suspend fun clearChainReferences(targetId: Long)

    @Query("UPDATE proxy_profiles SET chainProxyId = 0 WHERE chainProxyId IN (SELECT id FROM proxy_profiles WHERE groupId = :groupId)")
    suspend fun clearChainReferencesByGroup(groupId: Long)

    @Query("DELETE FROM proxy_profiles WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM proxy_profiles WHERE groupId = :groupId")
    suspend fun deleteByGroup(groupId: Long)
}

@Dao
interface GroupDao {
    @Query("SELECT * FROM proxy_groups ORDER BY id ASC")
    fun observeAll(): Flow<List<GroupEntity>>

    @Query("SELECT * FROM proxy_groups WHERE id = :id")
    suspend fun getById(id: Long): GroupEntity?

    @Query("SELECT COUNT(*) FROM proxy_groups")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: GroupEntity): Long

    @Update
    suspend fun update(entity: GroupEntity)

    @Query("DELETE FROM proxy_groups WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface RoutingRuleDao {
    @Query("SELECT * FROM routing_rules ORDER BY priority ASC, id ASC")
    fun observeAll(): Flow<List<RoutingRuleEntity>>

    @Query("SELECT COUNT(*) FROM routing_rules")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: RoutingRuleEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<RoutingRuleEntity>)

    @Update
    suspend fun update(entity: RoutingRuleEntity)

    @Query("DELETE FROM routing_rules WHERE id = :id")
    suspend fun deleteById(id: Long)
}
