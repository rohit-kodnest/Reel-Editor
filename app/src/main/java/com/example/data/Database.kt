package com.example.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "video_projects")
data class VideoProject(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val durationSec: Int,
    val localUri: String? = null,
    val dateCreated: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "generated_reels",
    foreignKeys = [
        ForeignKey(
            entity = VideoProject::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["projectId"])]
)
data class GeneratedReel(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val projectId: Long,
    val title: String,
    val startTimeSec: Float,
    val endTimeSec: Float,
    val filterName: String,
    val audioTrackName: String,
    val caption: String,
    val hashtags: String,
    val scriptHook: String,
    val viralScore: Int,
    val videoAspect: String = "9:16",
    val dateGenerated: Long = System.currentTimeMillis()
)

@Dao
interface VideoProjectDao {
    @Query("SELECT * FROM video_projects ORDER BY dateCreated DESC")
    fun getAllProjectsFlow(): Flow<List<VideoProject>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProject(project: VideoProject): Long

    @Query("SELECT * FROM video_projects WHERE id = :id LIMIT 1")
    suspend fun getProjectById(id: Long): VideoProject?

    @Delete
    suspend fun deleteProject(project: VideoProject)
}

@Dao
interface GeneratedReelDao {
    @Query("SELECT * FROM generated_reels WHERE projectId = :projectId ORDER BY dateGenerated DESC")
    fun getReelsForProjectFlow(projectId: Long): Flow<List<GeneratedReel>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReel(reel: GeneratedReel): Long

    @Query("SELECT * FROM generated_reels ORDER BY dateGenerated DESC")
    fun getAllReelsFlow(): Flow<List<GeneratedReel>>

    @Delete
    suspend fun deleteReel(reel: GeneratedReel)
}

@Database(entities = [VideoProject::class, GeneratedReel::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun videoProjectDao(): VideoProjectDao
    abstract fun generatedReelDao(): GeneratedReelDao
}

class ReelRepository(
    private val projectDao: VideoProjectDao,
    private val reelDao: GeneratedReelDao
) {
    val allProjects: Flow<List<VideoProject>> = projectDao.getAllProjectsFlow()
    val allReels: Flow<List<GeneratedReel>> = reelDao.getAllReelsFlow()

    fun getReelsForProject(projectId: Long): Flow<List<GeneratedReel>> {
        return reelDao.getReelsForProjectFlow(projectId)
    }

    suspend fun insertProject(project: VideoProject): Long {
        return projectDao.insertProject(project)
    }

    suspend fun deleteProject(project: VideoProject) {
        projectDao.deleteProject(project)
    }

    suspend fun insertReel(reel: GeneratedReel): Long {
        return reelDao.insertReel(reel)
    }

    suspend fun deleteReel(reel: GeneratedReel) {
        reelDao.deleteReel(reel)
    }
}
