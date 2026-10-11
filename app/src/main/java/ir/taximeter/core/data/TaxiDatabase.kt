package ir.taximeter.core.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        TaxiSessionEntity::class,
        FareConfigEntity::class,
        PassengerTripEntity::class,
        LocationPointEntity::class,
        DistanceSegmentEntity::class,
        TripDistanceSegmentEntity::class,
        TripEventEntity::class,
        FareAdjustmentEntity::class,
        AuditEventEntity::class
    ],
    version = 1,
    exportSchema = true
)
abstract class TaxiDatabase : RoomDatabase() {
    abstract fun taxiDao(): TaxiDao

    companion object {
        @Volatile private var INSTANCE: TaxiDatabase? = null

        fun getInstance(context: Context): TaxiDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    TaxiDatabase::class.java,
                    "taximeter.db"
                ).build().also { INSTANCE = it }
            }
    }
}
