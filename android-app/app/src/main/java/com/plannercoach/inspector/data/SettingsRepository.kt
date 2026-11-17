package com.plannercoach.inspector.data

import android.content.Context
import com.plannercoach.inspector.model.StudentProfileType

data class AppSettings(
    val geminiApiKey: String = "",
    val geminiModel: String = "gemini-4-hydrogen",
    val useNanoBananaBasic: Boolean = true,
    val highSchoolName: String = "김희균",
    val elementaryName: String = "초등학생",
    val bibleMemberName: String = "김희균",
    val bibleMemberPhone: String = "01023708994",
    val focusHabitKey: String = "BIBLE_AND_VOLUME",
    val googleDriveTreeUri: String = "",
    val googleAppsScriptWebhookUrl: String = "",
    val autoSyncToDrive: Boolean = true,
    val includeHandwritingPracticeInPdf: Boolean = true
) {
    fun nameFor(profile: StudentProfileType): String = when (profile) {
        StudentProfileType.HIGH_SCHOOL -> highSchoolName.ifBlank { "김희균" }
        StudentProfileType.ELEMENTARY -> elementaryName.ifBlank { "초등학생" }
    }
}

class SettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences("planner_coach_settings", Context.MODE_PRIVATE)

    fun load(): AppSettings = AppSettings(
        geminiApiKey = prefs.getString(KEY_API_KEY, "") ?: "",
        geminiModel = prefs.getString(KEY_MODEL, "gemini-4-hydrogen") ?: "gemini-4-hydrogen",
        useNanoBananaBasic = prefs.getBoolean(KEY_USE_NANO_BANANA, true),
        highSchoolName = prefs.getString(KEY_HS_NAME, "김희균") ?: "김희균",
        elementaryName = prefs.getString(KEY_EL_NAME, "초등학생") ?: "초등학생",
        bibleMemberName = prefs.getString(KEY_BIBLE_NAME, "김희균") ?: "김희균",
        bibleMemberPhone = prefs.getString(KEY_BIBLE_PHONE, "01023708994") ?: "01023708994",
        focusHabitKey = prefs.getString(KEY_FOCUS_HABIT, "BIBLE_AND_VOLUME") ?: "BIBLE_AND_VOLUME",
        googleDriveTreeUri = prefs.getString(KEY_DRIVE_URI, "") ?: "",
        googleAppsScriptWebhookUrl = prefs.getString(KEY_WEBHOOK_URL, "") ?: "",
        autoSyncToDrive = prefs.getBoolean(KEY_AUTO_SYNC, true),
        includeHandwritingPracticeInPdf = prefs.getBoolean(KEY_PDF_PRACTICE, true)
    )

    fun save(settings: AppSettings) {
        prefs.edit()
            .putString(KEY_API_KEY, settings.geminiApiKey.trim())
            .putString(KEY_MODEL, settings.geminiModel)
            .putBoolean(KEY_USE_NANO_BANANA, settings.useNanoBananaBasic)
            .putString(KEY_HS_NAME, settings.highSchoolName.trim())
            .putString(KEY_EL_NAME, settings.elementaryName.trim())
            .putString(KEY_BIBLE_NAME, settings.bibleMemberName.trim())
            .putString(KEY_BIBLE_PHONE, settings.bibleMemberPhone.trim())
            .putString(KEY_FOCUS_HABIT, settings.focusHabitKey)
            .putString(KEY_DRIVE_URI, settings.googleDriveTreeUri)
            .putString(KEY_WEBHOOK_URL, settings.googleAppsScriptWebhookUrl.trim())
            .putBoolean(KEY_AUTO_SYNC, settings.autoSyncToDrive)
            .putBoolean(KEY_PDF_PRACTICE, settings.includeHandwritingPracticeInPdf)
            .apply()
    }

    companion object {
        private const val KEY_API_KEY = "gemini_api_key"
        private const val KEY_MODEL = "gemini_model"
        private const val KEY_USE_NANO_BANANA = "use_nano_banana_basic"
        private const val KEY_HS_NAME = "hs_name"
        private const val KEY_EL_NAME = "el_name"
        private const val KEY_BIBLE_NAME = "bible_member_name"
        private const val KEY_BIBLE_PHONE = "bible_member_phone"
        private const val KEY_FOCUS_HABIT = "focus_habit_key"
        private const val KEY_DRIVE_URI = "drive_tree_uri"
        private const val KEY_WEBHOOK_URL = "apps_script_webhook"
        private const val KEY_AUTO_SYNC = "auto_sync_drive"
        private const val KEY_PDF_PRACTICE = "pdf_practice_lines"
    }
}
