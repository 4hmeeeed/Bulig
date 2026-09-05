package ph.bulig.app.store

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import ph.bulig.data.store.ReportRecord

/**
 * One stored report.
 *
 * A field-for-field mirror of [ReportRecord] and nothing else. Every rule about
 * what a column means, and the mapping to and from a `LocalReport`, lives in
 * `:data` where it is tested — `ReportRecordTest` asserts the column count, so
 * adding a field there without adding it here fails a test rather than silently
 * losing part of a report on a phone.
 *
 * Deliberately no `@Embedded` and no type converters: a flat table of primitives
 * is the shape a corrupt row can be reasoned about in, and this database will be
 * read by somebody debugging a field test with a SQLite browser.
 */
@Entity(tableName = "reports")
data class ReportEntity(
    @PrimaryKey
    @ColumnInfo(name = "packet_id") val packetId: String,
    @ColumnInfo(name = "emergency_id") val emergencyId: String,
    @ColumnInfo(name = "origin_device_id") val originDeviceId: String,
    @ColumnInfo(name = "created_at_device_ms") val createdAtDeviceMs: Long,

    @ColumnInfo(name = "hop_count") val hopCount: Int,
    @ColumnInfo(name = "ttl_remaining") val ttlRemaining: Int,
    @ColumnInfo(name = "ttl_initial") val ttlInitial: Int,

    val hmac: String?,
    @ColumnInfo(name = "route_path_json") val routePathJson: String,

    @ColumnInfo(name = "type_code") val typeCode: String,
    val description: String?,
    @ColumnInfo(name = "affected_count") val affectedCount: Int,
    @ColumnInfo(name = "children_count") val childrenCount: Int,
    @ColumnInfo(name = "elderly_count") val elderlyCount: Int,
    @ColumnInfo(name = "mobility_limited_count") val mobilityLimitedCount: Int,
    @ColumnInfo(name = "is_life_threatening") val isLifeThreatening: Boolean,
    @ColumnInfo(name = "vulnerability_notes") val vulnerabilityNotes: String?,
    val latitude: Double?,
    val longitude: Double?,
    @ColumnInfo(name = "accuracy_m") val accuracyM: Double?,
    @ColumnInfo(name = "location_provider") val locationProvider: String?,
    @ColumnInfo(name = "captured_at_ms") val capturedAtMs: Long?,

    @ColumnInfo(name = "delivery_state") val deliveryState: String,
    @ColumnInfo(name = "emergency_code") val emergencyCode: String?,
    @ColumnInfo(name = "priority_level") val priorityLevel: String?,
    @ColumnInfo(name = "handoffs_json") val handoffsJson: String,
    val synced: Boolean,
    @ColumnInfo(name = "permanent_failure") val permanentFailure: String?,
    @ColumnInfo(name = "attempt_count") val attemptCount: Int,
    @ColumnInfo(name = "last_attempt_at_ms") val lastAttemptAtMs: Long?,
    @ColumnInfo(name = "is_mine") val isMine: Boolean,
) {
    fun toRecord() = ReportRecord(
        packetId, emergencyId, originDeviceId, createdAtDeviceMs,
        hopCount, ttlRemaining, ttlInitial, hmac, routePathJson,
        typeCode, description, affectedCount, childrenCount, elderlyCount,
        mobilityLimitedCount, isLifeThreatening, vulnerabilityNotes,
        latitude, longitude, accuracyM, locationProvider, capturedAtMs,
        deliveryState, emergencyCode, priorityLevel, handoffsJson,
        synced, permanentFailure, attemptCount, lastAttemptAtMs, isMine,
    )

    companion object {
        fun from(r: ReportRecord) = ReportEntity(
            r.packetId, r.emergencyId, r.originDeviceId, r.createdAtDeviceMs,
            r.hopCount, r.ttlRemaining, r.ttlInitial, r.hmac, r.routePathJson,
            r.typeCode, r.description, r.affectedCount, r.childrenCount, r.elderlyCount,
            r.mobilityLimitedCount, r.isLifeThreatening, r.vulnerabilityNotes,
            r.latitude, r.longitude, r.accuracyM, r.locationProvider, r.capturedAtMs,
            r.deliveryState, r.emergencyCode, r.priorityLevel, r.handoffsJson,
            r.synced, r.permanentFailure, r.attemptCount, r.lastAttemptAtMs, r.isMine,
        )
    }
}

@Dao
interface ReportDao {

    /**
     * REPLACE, because a relayed packet arriving again with a higher hop count
     * is an update to the row we already hold rather than a new report. The
     * primary key is `packet_id`, which is minted once at origin and never
     * rewritten, so this cannot silently merge two different emergencies.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(report: ReportEntity)

    @Query("SELECT * FROM reports WHERE packet_id = :packetId LIMIT 1")
    fun get(packetId: String): ReportEntity?

    @Query("SELECT * FROM reports WHERE emergency_id = :emergencyId LIMIT 1")
    fun findByEmergencyId(emergencyId: String): ReportEntity?

    @Query("SELECT * FROM reports ORDER BY created_at_device_ms DESC")
    fun all(): List<ReportEntity>

    /**
     * A TTL-expired report is still pending: it can no longer be relayed, but
     * the phone holding it may yet find signal, and dropping it would discard a
     * report already carried across the barangay.
     */
    @Query("SELECT * FROM reports WHERE synced = 0 AND permanent_failure IS NULL")
    fun pendingSync(): List<ReportEntity>

    @Query("SELECT * FROM reports WHERE is_mine = 1 ORDER BY created_at_device_ms DESC")
    fun mine(): List<ReportEntity>

    @Query("SELECT * FROM reports WHERE is_mine = 0 ORDER BY created_at_device_ms DESC")
    fun carriedForOthers(): List<ReportEntity>

    @Query("DELETE FROM reports WHERE packet_id = :packetId")
    fun delete(packetId: String)

    @Query("SELECT COUNT(*) FROM reports")
    fun count(): Int
}

@Database(entities = [ReportEntity::class], version = 1, exportSchema = true)
abstract class ReportDatabase : RoomDatabase() {

    abstract fun reports(): ReportDao

    companion object {
        private const val NAME = "bulig-reports.db"

        @Volatile
        private var instance: ReportDatabase? = null

        /**
         * Opens the encrypted database.
         *
         * The passphrase comes from the Android Keystore, never from the APK —
         * a key compiled into the app protects nobody, since anyone who can read
         * the database file can also read the app that opens it.
         *
         * This is what artboard 06's promise that a report "sits on this phone,
         * encrypted" depends on. Until this path is exercised on a device that
         * claim is unverified, and `docs/LIMITATIONS.md` 9a says so.
         */
        fun open(context: Context, passphrase: ByteArray): ReportDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    ReportDatabase::class.java,
                    NAME,
                )
                    .openHelperFactory(SupportOpenHelperFactory(passphrase))
                    // No destructive migration. A schema change that wiped a
                    // resident's undelivered reports would be the single worst
                    // thing this app could do on an update.
                    .build()
                    .also { instance = it }
            }
    }
}
