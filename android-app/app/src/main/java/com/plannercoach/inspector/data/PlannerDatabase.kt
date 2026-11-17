package com.plannercoach.inspector.data

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
import com.plannercoach.inspector.model.DailyInspectionResult
import com.plannercoach.inspector.model.StudentProfileType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

@Entity(tableName = "daily_inspections")
data class DailyInspectionEntity(
    @PrimaryKey val id: String,
    val studentType: String,
    val dateIso: String,
    val habitScore: Int,
    val totalStudyMinutes: Int,
    val selfStudyMinutes: Int,
    val lectureMinutes: Int,
    val reviewMinutes: Int,
    val plannedTasksCount: Int,
    val completedTasksCount: Int,
    val payloadJson: String,
    val syncedToDrive: Boolean,
    val createdAtEpochMs: Long
) {
    fun toModel(): DailyInspectionResult =
        DailyInspectionResult.fromJson(JSONObject(payloadJson)).copy(syncedToDrive = syncedToDrive)

    companion object {
        fun fromModel(model: DailyInspectionResult): DailyInspectionEntity =
            DailyInspectionEntity(
                id = model.id,
                studentType = model.studentType.id,
                dateIso = model.dateIso,
                habitScore = model.habitScore,
                totalStudyMinutes = model.totalStudyMinutes,
                selfStudyMinutes = model.selfStudyMinutes,
                lectureMinutes = model.lectureMinutes,
                reviewMinutes = model.reviewMinutes,
                plannedTasksCount = model.plannedTasksCount,
                completedTasksCount = model.completedTasksCount,
                payloadJson = model.toJson().toString(),
                syncedToDrive = model.syncedToDrive,
                createdAtEpochMs = model.createdAtEpochMs
            )
    }
}

@Dao
interface DailyInspectionDao {
    @Query("SELECT * FROM daily_inspections WHERE studentType = :studentType ORDER BY dateIso DESC, createdAtEpochMs DESC")
    fun observeByStudent(studentType: String): Flow<List<DailyInspectionEntity>>

    @Query("SELECT * FROM daily_inspections WHERE studentType = :studentType ORDER BY dateIso DESC, createdAtEpochMs DESC")
    suspend fun getAllByStudent(studentType: String): List<DailyInspectionEntity>

    @Query("SELECT * FROM daily_inspections ORDER BY dateIso DESC, createdAtEpochMs DESC")
    suspend fun getAll(): List<DailyInspectionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: DailyInspectionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<DailyInspectionEntity>)

    @Query("UPDATE daily_inspections SET syncedToDrive = :synced WHERE id = :id")
    suspend fun markSynced(id: String, synced: Boolean)

    @Query("DELETE FROM daily_inspections WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Database(entities = [DailyInspectionEntity::class], version = 1, exportSchema = false)
abstract class PlannerDatabase : RoomDatabase() {
    abstract fun inspectionDao(): DailyInspectionDao

    companion object {
        @Volatile
        private var INSTANCE: PlannerDatabase? = null

        fun getInstance(context: Context): PlannerDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    PlannerDatabase::class.java,
                    "planner_coach_db"
                ).build().also { INSTANCE = it }
            }
    }
}

class PlannerRepository(private val dao: DailyInspectionDao) {
    fun observeInspections(studentType: StudentProfileType): Flow<List<DailyInspectionResult>> =
        dao.observeByStudent(studentType.id).map { list -> list.map { it.toModel() } }

    suspend fun getInspections(studentType: StudentProfileType): List<DailyInspectionResult> =
        dao.getAllByStudent(studentType.id).map { it.toModel() }

    suspend fun saveInspection(item: DailyInspectionResult) {
        dao.upsert(DailyInspectionEntity.fromModel(item))
    }

    suspend fun markSynced(id: String, synced: Boolean = true) {
        dao.markSynced(id, synced)
    }

    suspend fun deleteInspection(id: String) {
        dao.deleteById(id)
    }

    suspend fun exportAllToBackupJson(): String {
        val all = dao.getAll().map { it.toModel().toJson() }
        return JSONObject().apply {
            put("schemaVersion", 1)
            put("exportedAtEpochMs", System.currentTimeMillis())
            put("records", JSONArray(all))
        }.toString(2)
    }

    suspend fun importBackupJson(jsonString: String): Int {
        val root = JSONObject(jsonString)
        val arr = root.optJSONArray("records") ?: return 0
        val entities = buildList {
            for (i in 0 until arr.length()) {
                val model = DailyInspectionResult.fromJson(arr.getJSONObject(i))
                add(DailyInspectionEntity.fromModel(model))
            }
        }
        dao.upsertAll(entities)
        return entities.size
    }
}
