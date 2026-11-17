package com.plannercoach.inspector

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.plannercoach.inspector.data.PlannerDatabase
import com.plannercoach.inspector.data.PlannerRepository
import com.plannercoach.inspector.data.SettingsRepository
import com.plannercoach.inspector.export.PdfPrintAndShareManager
import com.plannercoach.inspector.network.Bible71Service
import com.plannercoach.inspector.network.GeminiInspectorService
import com.plannercoach.inspector.sync.GoogleDriveSyncManager
import com.plannercoach.inspector.ui.PlannerCoachApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val db = PlannerDatabase.getInstance(applicationContext)
        val repository = PlannerRepository(db.inspectionDao())
        val settingsRepository = SettingsRepository(applicationContext)
        val geminiService = GeminiInspectorService(applicationContext)
        val bible71Service = Bible71Service(applicationContext)
        val exportManager = PdfPrintAndShareManager(applicationContext)
        val driveSyncManager = GoogleDriveSyncManager(applicationContext, repository)

        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Color(0xFF2563EB),
                    secondary = Color(0xFF059669),
                    background = Color(0xFFF8FAFC),
                    surface = Color.White
                )
            ) {
                PlannerCoachApp(
                    repository = repository,
                    settingsRepository = settingsRepository,
                    geminiService = geminiService,
                    bible71Service = bible71Service,
                    exportManager = exportManager,
                    driveSyncManager = driveSyncManager
                )
            }
        }
    }
}
