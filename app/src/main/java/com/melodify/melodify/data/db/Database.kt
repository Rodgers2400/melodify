package com.melodify.melodify.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Update
import androidx.room.Delete
import androidx.room.Query
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.OnConflictStrategy
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.melodify.melodify.data.model.LyricsSource
import kotlinx.coroutines.flow.Flow

/* ---------------- Entities ---------------- */

@Entity(tableName = "tracks")
data class TrackEntity(
    @PrimaryKey val id: Long,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "artist") val artist: String,
    @ColumnInfo(name = "album") val album: String,
    @ColumnInfo(name = "duration") val duration: Long,
    @ColumnInfo(name = "path") val path: String,
    @ColumnInfo(name = "uri") val uri: String,
    @ColumnInfo(name = "mime_type") val mimeType: String,
    @ColumnInfo(name = "size") val size: Long,
    @ColumnInfo(name = "date_added") val dateAdded: Long,
    @ColumnInfo(name = "track_number") val trackNumber: Int,
    @ColumnInfo(name = "album_art_uri") val albumArtUri: String?,
    @ColumnInfo(name = "lyrics_source") val lyricsSource: String,
    @ColumnInfo(name = "lyrics_text") val lyricsText: String?
)

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "cover_uri") val coverUri: String?
)

@Entity(
    tableName = "playlist_songs",
    primaryKeys = ["playlist_id", "track_id"],
    indices = [androidx.room.Index("track_id")]
)
data class PlaylistSongCrossRef(
    @ColumnInfo(name = "playlist_id") val playlistId: Long,
    @ColumnInfo(name = "track_id") val trackId: Long,
    @ColumnInfo(name = "position") val position: Int
)

@Entity(tableName = "lyrics_cache", primaryKeys = ["track_id", "source"])
data class LyricsCacheEntity(
    @ColumnInfo(name = "track_id") val trackId: Long,
    @ColumnInfo(name = "source") val source: String,
    @ColumnInfo(name = "lines_json") val linesJson: String,
    @ColumnInfo(name = "plain_text") val plainText: String?,
    @ColumnInfo(name = "cached_at") val cachedAt: Long
)

/* ---------------- DAOs ---------------- */

@Dao
interface TrackDao {

    @Query("SELECT * FROM tracks ORDER BY title COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): TrackEntity?

    @Query("SELECT * FROM tracks WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<TrackEntity>

    @Query("SELECT id FROM tracks")
    suspend fun getAllIds(): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(tracks: List<TrackEntity>)

    @Query("DELETE FROM tracks WHERE id NOT IN (:keepIds)")
    suspend fun deleteExcept(keepIds: List<Long>)

    @Query("UPDATE tracks SET lyrics_source = :source, lyrics_text = :text WHERE id = :id")
    suspend fun updateLyrics(id: Long, source: String, text: String?)
}

@Dao
interface PlaylistDao {

    @Query("SELECT * FROM playlists ORDER BY created_at DESC")
    fun observeAll(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): PlaylistEntity?

    @Query("SELECT COUNT(*) FROM playlist_songs WHERE playlist_id = :playlistId")
    fun songCount(playlistId: Long): Flow<Int>

    @Insert
    suspend fun insert(playlist: PlaylistEntity): Long

    @Update
    suspend fun update(playlist: PlaylistEntity)

    @Delete
    suspend fun delete(playlist: PlaylistEntity)

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE playlists SET name = :newName, updated_at = :updatedAt WHERE id = :id")
    suspend fun rename(id: Long, newName: String, updatedAt: Long)

    // Cross refs
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addSong(ref: PlaylistSongCrossRef)

    @Delete
    suspend fun removeSong(ref: PlaylistSongCrossRef)

    @Query("DELETE FROM playlist_songs WHERE playlist_id = :playlistId AND track_id = :trackId")
    suspend fun removeSongById(playlistId: Long, trackId: Long)

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM playlist_songs WHERE playlist_id = :playlistId")
    suspend fun nextPosition(playlistId: Long): Int

    @Query(
        "SELECT t.* FROM tracks t INNER JOIN playlist_songs ps " +
        "ON ps.track_id = t.id WHERE ps.playlist_id = :playlistId " +
        "ORDER BY ps.position ASC"
    )
    fun songsInPlaylist(playlistId: Long): Flow<List<TrackEntity>>

    @Query("SELECT track_id FROM playlist_songs WHERE playlist_id = :playlistId ORDER BY position ASC")
    suspend fun orderedTrackIds(playlistId: Long): List<Long>
}

@Dao
interface LyricsCacheDao {

    @Query("SELECT * FROM lyrics_cache WHERE track_id = :trackId AND source = :source LIMIT 1")
    suspend fun find(trackId: Long, source: String): LyricsCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: LyricsCacheEntity)

    @Query("DELETE FROM lyrics_cache WHERE track_id = :trackId")
    suspend fun clearForTrack(trackId: Long)
}

/* ---------------- Converters ---------------- */

class Converters {
    @TypeConverter
    fun fromSource(s: LyricsSource): String = s.name

    @TypeConverter
    fun toSource(v: String): LyricsSource =
        runCatching { LyricsSource.valueOf(v) }.getOrDefault(LyricsSource.NONE)
}

/* ---------------- Database ---------------- */

@Database(
    entities = [TrackEntity::class, PlaylistEntity::class, PlaylistSongCrossRef::class, LyricsCacheEntity::class],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun trackDao(): TrackDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun lyricsCacheDao(): LyricsCacheDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "melodify.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { INSTANCE = it }
            }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) { /* future use */ }
        }
    }
}
