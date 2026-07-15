package com.mupa.agent.argos.database

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase

@Entity(tableName = "commands")
data class CommandEntity(
    @PrimaryKey val commandId: String,
    val command: String,
    val state: String,
    val priority: Int,
    val attempts: Int,
    val paramsJson: String,
    val executionToken: String,
    val updatedAt: Long,
)

@Entity(tableName = "configurations")
data class ConfigurationEntity(
    @PrimaryKey val scope: String,
    val configurationHash: String,
    val payloadJson: String,
    val updatedAt: Long,
)

@Entity(tableName = "apps")
data class AppInventoryEntity(
    @PrimaryKey val packageName: String,
    val appName: String,
    val versionName: String,
    val versionCode: Long,
    val lastUsedAt: Long?,
    val updatedAt: Long,
)

@Entity(tableName = "logs")
data class LogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val category: String,
    val level: String,
    val message: String,
    val payloadJson: String,
    val createdAt: Long,
)

@Entity(tableName = "ota_history")
data class OtaHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val packageName: String,
    val versionName: String,
    val state: String,
    val sha256: String?,
    val createdAt: Long,
)

@Entity(tableName = "location_history")
data class LocationHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float?,
    val capturedAt: Long,
)

@Entity(tableName = "geofence_events")
data class GeofenceEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val geofenceId: String,
    val eventType: String,
    val latitude: Double?,
    val longitude: Double?,
    val createdAt: Long,
)

@Entity(tableName = "health_snapshots")
data class HealthSnapshotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val ramPercent: Int,
    val cpuPercent: Int,
    val storagePercent: Int,
    val batteryPercent: Int,
    val temperatureCelsius: Double?,
    val wifiConnected: Boolean,
    val ipAddress: String?,
    val foregroundPackage: String?,
    val uptimeSeconds: Long,
    val agentVersion: String,
    val agentVersionCode: Long = 0L,
    val mplayerVersion: String?,
    val remoteVersion: String?,
    val capturedAt: Long,
)

@Entity(tableName = "executed_commands")
data class ExecutedCommandEntity(
    @PrimaryKey val executionToken: String,
    val commandId: String,
    val command: String,
    val status: String,
    val executedAt: Long,
)

@Entity(tableName = "last_heartbeat")
data class LastHeartbeatEntity(
    @PrimaryKey val slot: String = "device",
    val fingerprint: String,
    val payloadJson: String,
    val sentAt: Long,
)

@Dao
interface CommandDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: CommandEntity)

    @Query("SELECT * FROM commands WHERE commandId = :commandId LIMIT 1")
    suspend fun findById(commandId: String): CommandEntity?
}

@Dao
interface ConfigurationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ConfigurationEntity)

    @Query("SELECT * FROM configurations WHERE scope = :scope LIMIT 1")
    suspend fun findByScope(scope: String): ConfigurationEntity?
}

@Dao
interface AppInventoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<AppInventoryEntity>)

    @Query("DELETE FROM apps")
    suspend fun clear()
}

@Dao
interface LogDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: LogEntity): Long

    @Query("SELECT * FROM logs ORDER BY createdAt ASC LIMIT :limit")
    suspend fun listOldest(limit: Int): List<LogEntity>

    @Query("SELECT COUNT(*) FROM logs")
    suspend fun count(): Int

    @Query("DELETE FROM logs WHERE id IN (:ids)")
    suspend fun deleteIds(ids: List<Long>)
}

@Dao
interface OtaHistoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: OtaHistoryEntity): Long
}

@Dao
interface LocationHistoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: LocationHistoryEntity): Long
}

@Dao
interface GeofenceEventDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: GeofenceEventEntity): Long
}

@Dao
interface HealthSnapshotDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: HealthSnapshotEntity): Long
}

@Dao
interface ExecutedCommandDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ExecutedCommandEntity)

    @Query("SELECT * FROM executed_commands WHERE executionToken = :executionToken LIMIT 1")
    suspend fun findByToken(executionToken: String): ExecutedCommandEntity?
}

@Dao
interface LastHeartbeatDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: LastHeartbeatEntity)

    @Query("SELECT * FROM last_heartbeat WHERE slot = :slot LIMIT 1")
    suspend fun find(slot: String = "device"): LastHeartbeatEntity?
}

@Database(
    entities = [
        CommandEntity::class,
        ConfigurationEntity::class,
        AppInventoryEntity::class,
        LogEntity::class,
        OtaHistoryEntity::class,
        LocationHistoryEntity::class,
        GeofenceEventEntity::class,
        HealthSnapshotEntity::class,
        ExecutedCommandEntity::class,
        LastHeartbeatEntity::class,
    ],
    version = 3,
    exportSchema = false,
)
abstract class ArgosAgentDatabase : RoomDatabase() {
    abstract fun commandDao(): CommandDao
    abstract fun configurationDao(): ConfigurationDao
    abstract fun appInventoryDao(): AppInventoryDao
    abstract fun logDao(): LogDao
    abstract fun otaHistoryDao(): OtaHistoryDao
    abstract fun locationHistoryDao(): LocationHistoryDao
    abstract fun geofenceEventDao(): GeofenceEventDao
    abstract fun healthSnapshotDao(): HealthSnapshotDao
    abstract fun executedCommandDao(): ExecutedCommandDao
    abstract fun lastHeartbeatDao(): LastHeartbeatDao
}

object ArgosDatabaseProvider {
    @Volatile
    private var instance: ArgosAgentDatabase? = null

    fun get(context: Context): ArgosAgentDatabase {
        return instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                ArgosAgentDatabase::class.java,
                "argos_agent_v3.db",
            ).fallbackToDestructiveMigration().build().also { instance = it }
        }
    }
}
