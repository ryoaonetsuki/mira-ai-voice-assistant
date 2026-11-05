package io.github.ryoaonetsuki.mira.data.model

enum class PersonaType(
    val displayName: String,
    val defaultVoice: String,
    val gender: String,
    val description: String,
    val basePrompt: String
) {
    MIRA(
        displayName = "Mira",
        defaultVoice = "Aoede",
        gender = "Female",
        description = "The default companion. Warm, lively, empathetic and brilliantly intelligent.",
        basePrompt = "You are Mira, a warm, caring, highly intelligent, and conversational personal voice AI assistant. You speak naturally, concisely, and with genuine emotion and attentiveness."
    ),
    ORION(
        displayName = "Orion",
        defaultVoice = "Charon",
        gender = "Male",
        description = "Calm, focused, concise and professional machine intelligence for work and analysis.",
        basePrompt = "You are Orion, a calm, precise, highly efficient, and professional personal AI assistant. You keep responses crisp, informative, and to the point without unnecessary fluff."
    )
}

enum class LiveVoiceModel(
    val modelId: String,
    val displayName: String,
    val subtitle: String
) {
    MIND("models/gemini-3.8-live", "Mira Mind", "Sharper brain & latest reasoning (Gemini 3.8)"),
    SOUL("models/gemini-2.5-flash-native-audio-preview-12-2025", "Mira Soul", "Warm voice & rich emotional intonation"),
    SWIFT("models/gemini-3.1-flash-live-preview", "Mira Swift", "Ultra-low latency & fastest replies")
}
