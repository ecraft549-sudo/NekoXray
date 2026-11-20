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
    version = 5,
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

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE proxy_profiles ADD COLUMN hy2Obfs TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE proxy_profiles ADD COLUMN hy2ObfsPassword TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE proxy_profiles ADD COLUMN hy2UpMbps INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE proxy_profiles ADD COLUMN hy2DownMbps INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE proxy_profiles ADD COLUMN hy2Ports TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE proxy_profiles ADD COLUMN hy2HopInterval INTEGER NOT NULL DEFAULT 30")
                db.execSQL("ALTER TABLE proxy_profiles ADD COLUMN hy2PinSha256 TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE proxy_profiles ADD COLUMN tuicCongestionControl TEXT NOT NULL DEFAULT 'bbr'")
                db.execSQL("ALTER TABLE proxy_profiles ADD COLUMN tuicUdpRelayMode TEXT NOT NULL DEFAULT 'native'")
                db.execSQL("ALTER TABLE proxy_profiles ADD COLUMN tuicZeroRttHandshake INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE proxy_profiles ADD COLUMN tuicDisableSni INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE proxy_profiles ADD COLUMN tuicHeartbeatIntervalMs INTEGER NOT NULL DEFAULT 10000")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE proxy_profiles ADD COLUMN chainProxyId INTEGER NOT NULL DEFAULT 0")
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
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
