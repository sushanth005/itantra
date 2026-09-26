package com.itantra.model

/**
 * Represents a supported language in iTantra.
 *
 * @property code  BCP-47 language tag used in proto payloads and asset filenames
 * @property displayName  Human-readable name shown in the UI
 * @property nativeName  Name in the language's own script for the selector chip
 */
enum class Language(
    val code: String,
    val displayName: String,
    val nativeName: String,
) {
    HINDI("hi", "Hindi", "हिन्दी"),
    GUJARATI("gu", "Gujarati", "ગુજરાતી"),
    MARATHI("mr", "Marathi", "मराठी"),
    KANNADA("kn", "Kannada", "ಕನ್ನಡ"),
    MALAYALAM("ml", "Malayalam", "മലയാളം"),
    TAMIL("ta", "Tamil", "தமிழ்"),
    TELUGU("te", "Telugu", "తెలుగు"),
    ODIA("or", "Odia", "ଓଡ଼ିଆ"),
    BENGALI("bn", "Bengali", "বাংলা"),
    ENGLISH("en", "English", "English");

    companion object {
        fun fromCode(code: String): Language =
            entries.firstOrNull { it.code == code } ?: HINDI
    }
}

/** Operating mode of the app. */
enum class AppMode {
    /** Manual Push-to-Talk: STT runs only while the button is held. */
    PUSH_TO_TALK,

    /** Continuous background listening via VAD. */
    PHONE_MODE,
}

/** Role of this device in the current session. */
enum class DeviceRole {
    /** This device captures speech and sends text packets. */
    SENDER,

    /** This device receives text packets and plays synthesized speech. */
    RECEIVER,
}

/** Connectivity type for the wireless link. */
enum class LinkType {
    BLUETOOTH,
    WIFI_DIRECT,
}
