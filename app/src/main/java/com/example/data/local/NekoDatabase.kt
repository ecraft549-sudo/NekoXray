package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ProfileEntity::class,
        GroupEntity::class,
        RoutingRuleEntity::class,
    ],
    version = 3,
    exportSchema = false,
)
abstract class NekoDatabase : RoomDatabase() {
    abstract fun profileDao(): ProfileDao
    abstract fun groupDao(): GroupDao
    abstract fun routingRuleDao(): RoutingRuleDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE proxy_profiles ADD COLUMN sshAuthType TEXT NOT NULL DEFAULT 'PASSWORD'")
                db.execSQL("ALTER TABLE proxy_profiles ADD COLUMN sshPrivateKey TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE proxy_profiles ADD COLUMN sshPrivateKeyPassphrase TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE proxy_profiles ADD COLUMN sshPublicKey TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "UPDATE routing_rules SET domains = 'geosite:category-ru,domain:ru,domain:su,domain:xn--p1ai' " +
                        "WHERE domains = 'geosite:category-ru,domain:.ru,domain:.su'"
                )
            }
        }

        @Volatile
        private var INSTANCE: NekoDatabase? = null

        fun getInstance(context: Context): NekoDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    NekoDatabase::class.java,
                    "nekoxray.db",
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
