package com.shadowprotectors.alarmapp.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class DatabaseHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "travel_alarm.db"
        private const val DATABASE_VERSION = 1

        const val TABLE_DESTINATIONS = "destinations"
        const val COLUMN_ID = "id"
        const val COLUMN_NAME = "name"
        const val COLUMN_LATITUDE = "latitude"
        const val COLUMN_LONGITUDE = "longitude"
        const val COLUMN_RADIUS = "alert_radius_km"
        const val COLUMN_IS_PRESET = "is_preset"
        const val COLUMN_CREATED_AT = "created_at"
    }

    override fun onCreate(db: SQLiteDatabase) {
        val createTableQuery = """
            CREATE TABLE $TABLE_DESTINATIONS (
                $COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COLUMN_NAME TEXT NOT NULL,
                $COLUMN_LATITUDE REAL NOT NULL,
                $COLUMN_LONGITUDE REAL NOT NULL,
                $COLUMN_RADIUS REAL NOT NULL DEFAULT 0.5,
                $COLUMN_IS_PRESET INTEGER NOT NULL DEFAULT 0,
                $COLUMN_CREATED_AT INTEGER NOT NULL
            )
        """.trimIndent()
        db.execSQL(createTableQuery)

        // Prepopulate standard presets (India focus, Madurai Junction running example)
        insertDefaultPresets(db)
    }

    private fun insertDefaultPresets(db: SQLiteDatabase) {
        val presets = listOf(
            Destination(name = "🚆 Madurai Junction", latitude = 9.9196, longitude = 78.1100, isPreset = true),
            Destination(name = "🚆 Chennai Central", latitude = 13.0827, longitude = 80.2707, isPreset = true),
            Destination(name = "✈️ Chennai Airport", latitude = 12.9941, longitude = 80.1709, isPreset = true),
            Destination(name = "🚆 Coimbatore Junction", latitude = 10.9972, longitude = 76.9634, isPreset = true),
            Destination(name = "🚆 Bengaluru Majestic", latitude = 12.9781, longitude = 77.5696, isPreset = true)
        )

        for (dest in presets) {
            val values = ContentValues().apply {
                put(COLUMN_NAME, dest.name)
                put(COLUMN_LATITUDE, dest.latitude)
                put(COLUMN_LONGITUDE, dest.longitude)
                put(COLUMN_RADIUS, dest.alertRadiusKm)
                put(COLUMN_IS_PRESET, if (dest.isPreset) 1 else 0)
                put(COLUMN_CREATED_AT, dest.createdAt)
            }
            db.insert(TABLE_DESTINATIONS, null, values)
        }
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_DESTINATIONS")
        onCreate(db)
    }

    fun insertDestination(dest: Destination): Long {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COLUMN_NAME, dest.name)
            put(COLUMN_LATITUDE, dest.latitude)
            put(COLUMN_LONGITUDE, dest.longitude)
            put(COLUMN_RADIUS, dest.alertRadiusKm)
            put(COLUMN_IS_PRESET, if (dest.isPreset) 1 else 0)
            put(COLUMN_CREATED_AT, System.currentTimeMillis())
        }
        return db.insert(TABLE_DESTINATIONS, null, values)
    }

    fun getAllDestinations(): List<Destination> {
        val list = mutableListOf<Destination>()
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT * FROM $TABLE_DESTINATIONS ORDER BY $COLUMN_IS_PRESET DESC, $COLUMN_CREATED_AT DESC", null)

        cursor.use {
            while (it.moveToNext()) {
                val id = it.getLong(it.getColumnIndexOrThrow(COLUMN_ID))
                val name = it.getString(it.getColumnIndexOrThrow(COLUMN_NAME))
                val lat = it.getDouble(it.getColumnIndexOrThrow(COLUMN_LATITUDE))
                val lng = it.getDouble(it.getColumnIndexOrThrow(COLUMN_LONGITUDE))
                val radius = it.getDouble(it.getColumnIndexOrThrow(COLUMN_RADIUS))
                val isPreset = it.getInt(it.getColumnIndexOrThrow(COLUMN_IS_PRESET)) == 1
                val createdAt = it.getLong(it.getColumnIndexOrThrow(COLUMN_CREATED_AT))

                list.add(Destination(id, name, lat, lng, radius, isPreset, createdAt))
            }
        }
        return list
    }

    fun deleteDestination(id: Long): Boolean {
        val db = writableDatabase
        return db.delete(TABLE_DESTINATIONS, "$COLUMN_ID = ?", arrayOf(id.toString())) > 0
    }
}
