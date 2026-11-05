package io.github.ryoaonetsuki.mira.data.model

data class VoiceInfo(
    val name: String,
    val style: String,
    val isFemale: Boolean
)

object VoiceCatalog {
    val femaleVoices = listOf(
        VoiceInfo("Aoede", "Breezy", true),
        VoiceInfo("Kore", "Firm", true),
        VoiceInfo("Leda", "Youthful", true),
        VoiceInfo("Zephyr", "Bright", true),
        VoiceInfo("Laomedeia", "Upbeat", true),
        VoiceInfo("Despina", "Smooth", true),
        VoiceInfo("Erinome", "Clear", true),
        VoiceInfo("Callirrhoe", "Easy-going", true),
        VoiceInfo("Autonoe", "Bright", true),
        VoiceInfo("Gacrux", "Mature", true),
        VoiceInfo("Pulcherrima", "Forward", true),
        VoiceInfo("Sulafat", "Warm", true),
        VoiceInfo("Vindemiatrix", "Gentle", true)
    )

    val maleVoices = listOf(
        VoiceInfo("Algenib", "Gravelly", false),
        VoiceInfo("Charon", "Informative", false),
        VoiceInfo("Fenrir", "Excitable", false),
        VoiceInfo("Puck", "Upbeat", false),
        VoiceInfo("Orus", "Firm", false),
        VoiceInfo("Enceladus", "Breathy", false),
        VoiceInfo("Iapetus", "Clear", false),
        VoiceInfo("Umbriel", "Easy-going", false),
        VoiceInfo("Algieba", "Smooth", false),
        VoiceInfo("Rasalgethi", "Informative", false),
        VoiceInfo("Alnilam", "Firm", false),
        VoiceInfo("Schedar", "Even", false),
        VoiceInfo("Achird", "Friendly", false),
        VoiceInfo("Zubenelgenubi", "Casual", false),
        VoiceInfo("Sadachbia", "Lively", false),
        VoiceInfo("Sadaltager", "Knowledgeable", false),
        VoiceInfo("Achernar", "Soft", false)
    )

    val allVoices: List<VoiceInfo> = femaleVoices + maleVoices

    val supportedLanguages = listOf(
        "English (US / Global)",
        "Bangla (Bengali)",
        "Hindi",
        "Urdu",
        "Arabic",
        "Spanish",
        "French",
        "German",
        "Japanese",
        "Korean",
        "Portuguese",
        "Russian",
        "Turkish",
        "Indonesian",
        "Vietnamese",
        "Thai",
        "Tamil",
        "Telugu",
        "Marathi",
        "Gujarati",
        "Punjabi",
        "Malayalam",
        "Kannada",
        "Italian",
        "Dutch",
        "Polish",
        "Swedish",
        "Ukrainian",
        "Greek",
        "Chinese (Mandarin)"
    )
}
