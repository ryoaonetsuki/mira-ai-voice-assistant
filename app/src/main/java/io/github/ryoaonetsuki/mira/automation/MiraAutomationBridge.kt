package io.github.ryoaonetsuki.mira.automation

import android.content.Context
import io.github.ryoaonetsuki.mira.automation.agent.AutomationPort
import io.github.ryoaonetsuki.mira.automation.agent.ScreenAutomationService
import io.github.ryoaonetsuki.mira.automation.agent.SkillMemoryService
import io.github.ryoaonetsuki.mira.automation.agent.TaskExecutor
import io.github.ryoaonetsuki.mira.automation.agent.TaskStatus
import io.github.ryoaonetsuki.mira.backend.ApiKeyResolver
import io.github.ryoaonetsuki.mira.backend.KeySource
import io.github.ryoaonetsuki.mira.data.local.entities.ChatMessageEntity
import io.github.ryoaonetsuki.mira.data.preferences.SecurePreferences
import io.github.ryoaonetsuki.mira.network.GeminiRestClient

data class AutomationResult(
    val status: TaskStatus,
    val message: String,
    val stepsUsed: Int
)

class MiraAutomationBridge(
    private val context: Context,
    private val apiKey: suspend () -> String
) {
    @Volatile
    private var activeExecutor: TaskExecutor? = null

    val isRunning: Boolean
        get() = activeExecutor?.isRunning == true

    fun cancel() {
        activeExecutor?.cancel()
    }

    suspend fun executeGoal(goal: String): AutomationResult {
        if (isRunning) {
            return AutomationResult(TaskStatus.FAILED, "Another task is already running", 0)
        }

        val automation: AutomationPort = ScreenAutomationService(context)
        val skills = SkillMemoryService.getInstance(context)
        val restClient = GeminiRestClient()

        val systemPrompt = "You control an Android phone. Reply with ONLY one JSON action object {action, params, reasoning, is_complete}. Actions: click_text, click_at, type_text, press_enter, scroll, swipe, press_back, press_home, open_app, wait, done. Screen description and goal are provided. Never invent coordinates outside the screen. If the goal is complete, reply done."

        val thinkLambda: suspend (String, String, List<Pair<String, String>>) -> String = { currentGoal, screenDescription, history ->
            val key = apiKey()
            val entities = ArrayList<ChatMessageEntity>(history.size + 1)
            for ((role, content) in history) {
                entities.add(
                    ChatMessageEntity(
                        conversationId = "automation",
                        role = if (role == "assistant" || role == "model") "model" else "user",
                        content = content
                    )
                )
            }
            entities.add(
                ChatMessageEntity(
                    conversationId = "automation",
                    role = "user",
                    content = "Goal: $currentGoal\nScreen:\n$screenDescription"
                )
            )

            val chatResult = restClient.generateChatResponse(
                apiKey = key,
                history = entities,
                systemPrompt = systemPrompt,
                model = "gemini-3.8-flash",
                enableThinking = false,
                isChatCall = false
            )
            if (chatResult.isSuccess) {
                chatResult.getOrNull()?.text ?: ""
            } else {
                throw chatResult.exceptionOrNull() ?: Exception("LLM call failed")
            }
        }

        val thinkVisionLambda: suspend (String, String, List<Pair<String, String>>, ByteArray) -> String = { currentGoal, screenDescription, history, screenshotBytes ->
            val key = apiKey()
            val entities = ArrayList<ChatMessageEntity>(history.size + 1)
            for ((role, content) in history) {
                entities.add(
                    ChatMessageEntity(
                        conversationId = "automation",
                        role = if (role == "assistant" || role == "model") "model" else "user",
                        content = content
                    )
                )
            }
            entities.add(
                ChatMessageEntity(
                    conversationId = "automation",
                    role = "user",
                    content = "Goal: $currentGoal\nScreen:\n$screenDescription"
                )
            )

            val visionResult = restClient.generateVision(
                apiKey = key,
                history = entities,
                imageBytes = screenshotBytes,
                systemPrompt = systemPrompt,
                model = "gemini-3.8-flash",
                enableThinking = false
            )
            if (visionResult.isSuccess) {
                visionResult.getOrNull()?.text ?: ""
            } else {
                throw visionResult.exceptionOrNull() ?: Exception("LLM vision call failed")
            }
        }

        val executor = TaskExecutor(
            automation = automation,
            skills = skills,
            think = thinkLambda,
            thinkWithVision = thinkVisionLambda
        )
        activeExecutor = executor
        return try {
            val result = executor.executeGoal(goal)
            AutomationResult(
                status = result.status,
                message = result.message,
                stepsUsed = result.stepsUsed
            )
        } finally {
            activeExecutor = null
        }
    }

    suspend fun executeSkill(skill: io.github.ryoaonetsuki.mira.automation.agent.Skill): AutomationResult {
        if (isRunning) {
            return AutomationResult(TaskStatus.FAILED, "Another task is already running", 0)
        }

        val automation: AutomationPort = ScreenAutomationService(context)
        val skills = SkillMemoryService.getInstance(context)

        val executor = TaskExecutor(
            automation = automation,
            skills = skills,
            think = null
        )
        activeExecutor = executor
        return try {
            val result = executor.replaySkillDirect(skill)
            AutomationResult(
                status = result.status,
                message = result.message,
                stepsUsed = result.stepsUsed
            )
        } finally {
            activeExecutor = null
        }
    }

    companion object {
        @Volatile
        private var instance: MiraAutomationBridge? = null

        fun getInstance(context: Context): MiraAutomationBridge {
            return instance ?: synchronized(this) {
                instance ?: MiraAutomationBridge(context.applicationContext) {
                    val appContext = context.applicationContext
                    val securePrefs = SecurePreferences(appContext)
                    val resolver = ApiKeyResolver(appContext)
                    when (val keySource = resolver.resolveKey()) {
                        is KeySource.UserKey -> keySource.key
                        else -> securePrefs.getGeminiApiKey() ?: ""
                    }
                }.also { instance = it }
            }
        }

        private val INTENT_PATTERNS = listOf(
            Regex("""\bplay\s+(?:music|song|songs|video|videos|some\s+music)\b""", RegexOption.IGNORE_CASE),
            Regex("""\bplay\s+.+\s+on\s+(?:youtube|spotify|music|ইউটিউব|স্পটিফাই)\b""", RegexOption.IGNORE_CASE),
            Regex("""\bmessage\s+.+\s+on\s+(whatsapp|মেসেজ|হোয়াটসঅ্যাপ)\b""", RegexOption.IGNORE_CASE),
            Regex("""\btext\s+.+\s+on\s+(whatsapp|মেসেজ|হোয়াটসঅ্যাপ)\b""", RegexOption.IGNORE_CASE),
            Regex("""\bcall\s+.+\s+on\s+(whatsapp|ফোন|হোয়াটসঅ্যাপ)\b""", RegexOption.IGNORE_CASE),
            Regex("""\b(control|operate)\s+(the\s+|my\s+|this\s+)?phone\b""", RegexOption.IGNORE_CASE),
            Regex("""\bphone\s+control\b""", RegexOption.IGNORE_CASE),
            Regex("""ফোন\s+(কন্ট্রোল|নিয়ন্ত্রণ)""", RegexOption.IGNORE_CASE),
            Regex("""\b(gaan|gan)\s+(bajao|chalao)\b""", RegexOption.IGNORE_CASE),
            Regex("""(গান|মিউজিক|music)\s*(চালাও|বাজাও|প্লে\s*করো|শোনাও)""", RegexOption.IGNORE_CASE),
            Regex("""(.+)\s*(খুলো|খোলো|ওপেন\s*করো|চালু\s*করো|চালাও)""", RegexOption.IGNORE_CASE)
        )

        private val LATIN_VERBS = setOf(
            // en
            "open", "launch", "start", "play", "close", "stop", "message", "text", "send", "call",
            "search", "find", "show", "turn on", "turn off", "set", "take a photo",
            "control", "operate",
            // bn (Roman)
            "kholo", "khulo", "chalao", "chalaw", "pathao", "pathaw",
            "bajao", "bajaw", "dekhao", "dekhaw", "koro", "korun", "koren",
            // es
            "abre", "abrir", "ábreme", "reproduce", "reproducir", "pon", "poner",
            "cierra", "cerrar", "detén", "detener", "para", "parar", "llama", "llamar",
            "envía", "enviar", "manda", "mandar", "busca", "buscar", "muestra", "mostrar",
            // fr
            "ouvre", "ouvrir", "lance", "lancer", "joue", "jouer", "mets", "mettre",
            "ferme", "fermer", "arrête", "arrêter", "appelle", "appeler",
            "envoie", "envoyer", "cherche", "chercher", "montre", "montrer",
            // de
            "öffne", "öffnen", "starte", "starten", "spiel", "spiele", "spielen", "abspielen",
            "schließe", "schließen", "stoppe", "stoppen", "ruf", "rufe", "anrufen",
            "sende", "senden", "schick", "schicke", "schicken", "suche", "suchen", "zeig", "zeige", "zeigen",
            // pt
            "abra", "toque", "toca", "tocar", "reproduza", "feche", "fecha", "fechar",
            "pare", "ligue", "liga", "ligar", "envie", "envia", "mande", "procure", "procurar",
            "busque", "mostre", "mostra",
            // tr
            "aç", "açın", "oynat", "çal", "başlat", "kapat", "durdur", "ara", "gönder", "mesaj at", "bul", "göster",
            // id
            "buka", "putar", "mainkan", "nyalakan", "tutup", "hentikan", "telepon", "hubungi",
            "kirim", "cari", "tampilkan", "perlihatkan",
            // vi
            "mở", "bật", "phát", "chơi", "đóng", "tắt", "dừng", "gọi", "gửi", "nhắn",
            "tìm", "tìm kiếm", "hiển thị", "cho xem",
            // it
            "apri", "aprire", "avvia", "avviare", "riproduci", "riprodurre", "metti", "mettere",
            "chiudi", "chiudere", "ferma", "fermare", "chiama", "chiamare",
            "invia", "inviare", "cerca", "cercare", "mostrare",
            // nl
            "openen", "speel", "afspelen", "sluit", "sluiten", "bel", "bellen",
            "stuur", "sturen", "verzenden", "zoek", "zoeken", "toon", "tonen", "laat zien",
            // pl
            "otwórz", "otworzyć", "uruchom", "włącz", "odtwórz", "zagraj", "puść",
            "zamknij", "wyłącz", "zatrzymaj", "zadzwoń", "wyślij", "napisz",
            "szukaj", "wyszukaj", "znajdź", "pokaż",
            // sv
            "öppna", "spela", "starta", "stäng", "stoppa", "ring", "skicka", "sök", "hitta", "visa"
        )

        private val SUBSTRING_VERBS = listOf(
            // bn
            "খুলো", "খোলো", "চালাও", "পাঠাও", "কল", "খুঁজো", "কন্ট্রোল", "নিয়ন্ত্রণ",
            "ওপেন", "চালু", "বাজাও", "প্লে", "শোনাও", "বন্ধ", "করো", "কর", "দেখাও",
            // hi
            "खोलो", "खोलें", "खोल", "चलाओ", "चलाएं", "बजाओ", "बजाएं", "बंद", "रोको",
            "कॉल", "फोन करो", "भेजो", "भेजें", "ढूंढो", "खोजो", "दिखाओ", "दिखाएं",
            // ur
            "کھولو", "کھولیں", "چلاؤ", "چلائیں", "بجاؤ", "بند", "روکو",
            "کال", "فون کرو", "بھیجو", "بھیجیں", "تلاش", "ڈھونڈو", "دکھاؤ", "دکھائیں",
            // ar
            "افتح", "افتحي", "فتح", "شغل", "شغلي", "تشغيل", "أغلق", "اقفل", "أوقف",
            "اتصل", "كلم", "أرسل", "ارسل", "ابحث", "اعرض", "أظهر",
            // ja
            "開いて", "開く", "開け", "起動", "再生", "流して", "かけて", "閉じて", "閉じる",
            "止めて", "停止", "電話して", "発信", "送って", "送信", "検索", "探して", "見せて", "表示",
            // ko
            "열어", "열어줘", "열기", "켜줘", "켜", "틀어", "틀어줘", "재생",
            "닫아", "꺼줘", "멈춰", "중지", "전화해", "걸어", "보내", "전송", "검색", "찾아", "보여줘", "보여",
            // ru
            "открой", "открыть", "запусти", "запустить", "включи", "включить", "играй", "воспроизведи",
            "закрой", "закрыть", "выключи", "останови", "позвони", "набери",
            "отправь", "пошли", "напиши", "найди", "поищи", "искать", "покажи", "показать",
            // th
            "เปิด", "เล่น", "ปิด", "หยุด", "โทร", "ส่ง", "ค้นหา", "หา", "แสดง", "โชว์",
            // ta
            "திற", "திறக்கவும்", "போடு", "இயக்கு", "வாசி", "மூடு", "நிறுத்து", "அழை", "கால்", "அனுப்பு", "தேடு", "காட்டு",
            // te
            "తెరువు", "తెరవండి", "ఓపెన్", "ప్లే", "పెట్టు", "మూసివేయి", "ఆపు", "కాల్", "పంపు", "పంపించు", "వెతుకు", "చూపించు", "చూపు",
            // mr
            "उघड", "उघडा", "चालू", "लाव", "लावा", "वाजव", "वाजवा", "बंद", "थांबव", "कॉल", "पाठव", "पाठवा", "शोध", "शोधा", "दाखव", "दाखवा",
            // gu
            "ખોલો", "ખોલ", "ચલાવો", "વગાડો", "વગાડ", "બંધ", "રોકો", "કોલ", "મોકલો", "મોકલ", "શોધો", "બતાવો", "બતાવ",
            // pa
            "ਖੋਲ੍ਹੋ", "ਖੋਲੋ", "ਚਲਾਓ", "ਵਜਾਓ", "ਬੰਦ", "ਰੋਕੋ", "ਕਾਲ", "ਭੇਜੋ", "ਲੱਭੋ", "ਦਿਖਾਓ", "ਵਿਖਾਓ",
            // ml
            "തുറക്കൂ", "തുറക്കുക", "തുറക്ക്", "പ്ലേ", "വയ്ക്കൂ", "പാടൂ", "അടയ്ക്കൂ", "നിർത്തൂ", "വിളിക്കൂ", "കോൾ", "അയയ്ക്കൂ", "തിരയൂ", "കാണിക്കൂ",
            // kn
            "ತೆರೆ", "ತೆರೆಯಿರಿ", "ಓಪನ್", "ಪ್ಲೇ", "ಹಾಕು", "ಹಾಕಿ", "ಮುಚ್ಚು", "ನಿಲ್ಲಿಸು", "ಕರೆ", "ಕಾಲ್", "ಕಳುಹಿಸು", "ಕಳುಹಿಸಿ", "ಹುಡುಕು", "ತೋರಿಸು", "ತೋರಿಸಿ",
            // uk
            "відкрий", "відкрити", "запусти", "увімкни", "включи", "відтвори", "грай",
            "закрий", "вимкни", "зупини", "подзвони", "зателефонуй", "надішли", "відправ", "знайди", "шукай", "покажи", "показати",
            // el
            "άνοιξε", "ανοίξτε", "παίξε", "βάλε", "κλείσε", "σταμάτα", "κάλεσε", "πάρε", "στείλε", "ψάξε", "αναζήτησε", "δείξε",
            // zh
            "打开", "開啟", "开启", "启动", "播放", "放", "听", "关闭", "關閉", "停止",
            "打电话", "拨打", "发消息", "发送", "發送", "搜索", "搜尋", "查找", "找", "显示", "顯示", "看看"
        )

        private val KNOWN_APPS = setOf(
            // Core Latin brands & nouns
            "youtube", "whatsapp", "maps", "chrome", "camera", "messages",
            "phone", "gmail", "spotify", "facebook", "settings", "app", "music",
            "song", "songs", "gaan", "gan", "sms",
            // Multilingual Latin media/app nouns (es, fr, de, pt, tr, id, vi, it, nl, pl, sv)
            "música", "musica", "canción", "cancion", "musique", "chanson", "musik", "lied",
            "müzik", "muzik", "şarkı", "sarki", "lagu", "nhạc", "bài hát", "canzone",
            "muziek", "nummer", "muzyka", "muzykę", "piosenka", "piosenkę", "låt",
            "cámara", "camara", "caméra", "appareil photo", "kamera", "máy ảnh", "fotocamera", "aparat",
            "ajustes", "configuración", "configuracion", "paramètres", "parametres", "réglages",
            "einstellungen", "configurações", "configuracoes", "ayarlar", "pengaturan", "setelan",
            "cài đặt", "impostazioni", "instellingen", "ustawienia", "inställningar",
            "mensajes", "nachrichten", "mensagens", "mesajlar", "pesan", "tin nhắn", "messaggi",
            "berichten", "wiadomości", "meddelanden",
            "teléfono", "telefono", "téléphone", "telefon", "telepon", "điện thoại", "telefoon",
            "mapas", "cartes", "karten", "haritalar", "harita", "peta", "bản đồ", "mappe", "kaarten", "mapy", "kartor",
            // bn
            "ইউটিউব", "গান", "মিউজিক", "স্পটিফাই", "হোয়াটসঅ্যাপ", "ক্রোম",
            "ক্যামেরা", "সেটিংস", "মেসেজ", "কল", "ফোন", "ফেসবুক", "জিমেইল", "ম্যাপস", "অ্যাপ",
            // hi & mr
            "यूट्यूब", "युट्युब", "गाना", "गीत", "संगीत", "म्यूजिक", "म्युझिक", "गाणं", "गाणे",
            "स्पॉटिफाई", "स्पॉटिफाय", "व्हाट्सएप", "वॉट्सऐप", "व्हॉट्सअॅप", "क्रोम", "कैमरा", "कॅमेरा",
            "सेटिंग्स", "सेटिंग", "सेटिंग्ज", "मैसेज", "मेसेज", "संदेश", "फोन", "फेसबुक", "जीमेल", "मैप्स", "मॅप्स", "नक्शा",
            // ur
            "یوٹیوب", "گانا", "موسیقی", "سپوٹیفائی", "واٹس ایپ", "کروم", "کیمرہ", "سیٹنگز", "میسج", "پیغام", "فون", "فیس بک", "جی میل", "میپس", "نقشہ",
            // ar
            "يوتيوب", "موسيقى", "أغنية", "اغنية", "اغاني", "سبوتيفاي", "واتساب", "واتس اب", "كروم", "كاميرا",
            "الإعدادات", "اعدادات", "رسائل", "رسالة", "هاتف", "تلفون", "فيسبوك", "جيميل", "خرائط",
            // ja
            "ユーチューブ", "音楽", "曲", "歌", "スポティファイ", "ワッツアップ", "クローム", "カメラ", "設定", "メッセージ", "電話", "フェイスブック", "ジーメール", "マップ", "地図",
            // ko
            "유튜브", "음악", "노래", "스포티파이", "왓츠앱", "크롬", "카메라", "설정", "메시지", "문자", "전화", "페이스북", "지메일", "지도", "맵",
            // ru & uk
            "ютуб", "ютьюб", "музыка", "музыку", "песня", "песню", "музика", "музику", "пісня", "пісню",
            "спотифай", "спотіфай", "ватсап", "вотсап", "хром", "камера", "камеру", "настройки", "налаштування",
            "сообщения", "смс", "повідомлення", "телефон", "фейсбук", "джимейл", "карты", "карти",
            // th
            "ยูทูบ", "ยูทูป", "เพลง", "ดนตรี", "สปอติฟาย", "วอทส์แอพ", "โครม", "กล้อง", "ตั้งค่า", "การตั้งค่า", "ข้อความ", "โทรศัพท์", "เฟซบุ๊ก", "เฟสบุ๊ค", "จีเมล", "แผนที่",
            // ta
            "யூடியூப்", "பாடல்", "பாட்டு", "இசை", "ஸ்பாட்டிஃபை", "வாட்ஸ்அப்", "குரோம்", "கேமரா", "அமைப்புகள்", "செய்திகள்", "போன்", "ஃபேஸ்புக்", "ஜிமெயில்", "மேப்ஸ்", "வரைபடம்",
            // te
            "యూట్యూబ్", "పాట", "సంగీతం", "స్పాటిఫై", "వాట్సాప్", "క్రోమ్", "కెమెరా", "సెట్టింగ్స్", "మెసేజ్", "ఫోన్", "ఫేస్బుక్", "జిమెయిల్", "మ్యాప్స్",
            // gu
            "યુટ્યુબ", "ગીત", "સંગીત", "સ્પોટિફાઇ", "વોટ્સએપ", "ક્રોમ", "કેમેરા", "સેટિંગ્સ", "મેસેજ", "ફોન", "ફેસબુક", "જીમેલ", "મેપ્સ",
            // pa
            "ਯੂਟਿਊਬ", "ਗਾਣਾ", "ਗੀਤ", "ਸੰਗੀਤ", "ਸਪੋਟੀਫਾਈ", "ਵਟਸਐਪ", "ਕ੍ਰੋਮ", "ਕੈਮਰਾ", "ਸੈਟਿੰਗਾਂ", "ਮੈਸੇਜ", "ਫ਼ੋਨ", "ਫੇਸਬੁੱਕ", "ਜੀਮੇਲ", "ਮੈਪਸ",
            // ml
            "യൂട്യൂബ്", "പാട്ട്", "സംഗീതം", "സ്പോട്ടിഫൈ", "വാട്ട്സ്ആപ്പ്", "ക്രോം", "ക്യാമറ", "ക്രമീകരണങ്ങൾ", "സന്ദേശങ്ങൾ", "ഫോൺ", "ഫേസ്ബുക്ക്", "ജിമെയിൽ", "മാപ്സ്",
            // kn
            "ಯೂಟ್ಯೂಬ್", "ಹಾಡು", "ಸಂಗೀತ", "ಸ್ಪಾಟಿಫೈ", "ವಾಟ್ಸಾಪ್", "ಕ್ರೋಮ್", "ಕ್ಯಾಮೆರಾ", "ಸೆಟ್ಟಿಂಗ್ಸ್", "ಮೆಸೇಜ್", "ಫೋನ್", "ಫೇಸ್ಬುಕ್", "ಜಿಮೇಲ್", "ಮ್ಯಾಪ್ಸ್",
            // el
            "γιουτιουμπ", "μουσική", "τραγούδι", "σπότιφαϊ", "γουατσαπ", "γούατσαπ", "κρομ", "κάμερα", "ρυθμίσεις", "μηνύματα", "τηλέφωνο", "φέισμπουκ", "τζιμέιλ", "χάρτες",
            // zh
            "油管", "音乐", "音樂", "歌曲", "浏览器", "瀏覽器", "相机", "相機", "设置", "設置", "短信", "信息", "訊息", "电话", "脸书", "臉書", "地图", "地圖"
        )

        private val TARGET_APP_CANONICAL_MAP: List<Pair<List<String>, String>> = listOf(
            listOf(
                "স্পটিফাই", "स्पॉटिफाई", "स्पॉटिफाय", "سپوٹیفائی", "سبوتيفاي", "スポティファイ", "스포티파이",
                "спотифай", "спотіфай", "สปอติฟาย", "ஸ்பாட்டிஃபை", "స్పాటిఫై", "સ્પોટિફાઇ", "ਸਪੋਟੀਫਾਈ",
                "സ്പോട്ടിഫൈ", "ಸ್ಪಾಟಿಫೈ", "σπότιφαϊ", "spotify"
            ) to "com.spotify.music",
            listOf(
                "ইউটিউব", "यूट्यूब", "युट्युब", "یوٹیوب", "يوتيوب", "ユーチューブ", "유튜브",
                "ютуб", "ютьюб", "ยูทูบ", "ยูทูป", "யூடியூப்", "యూట్యూబ్", "યુટ્યુબ", "ਯੂਟਿਊਬ",
                "യൂട്യൂബ്", "ಯೂಟ್ಯೂಬ್", "γιουτιουμπ", "油管", "youtube"
            ) to "com.google.android.youtube",
            listOf(
                "হোয়াটসঅ্যাপ", "व्हाट्सएप", "वॉट्सऐप", "व्हॉट्सअॅप", "واٹس ایپ", "واتساب", "واتس اب",
                "ワッツアップ", "왓츠앱", "ватсап", "вотсап", "วอทส์แอพ", "வாட்ஸ்அப்", "వాట్సాప్",
                "વોટ્સએપ", "ਵਟਸਐਪ", "വാട്ട്സ്ആപ്പ്", "ವಾಟ್ಸಾಪ್", "γουατσαπ", "γούατσαπ", "whatsapp"
            ) to "com.whatsapp",
            listOf(
                "ক্রোম", "क्रोम", "کروم", "كروم", "クローム", "크롬", "хром", "โครม",
                "குரோம்", "క్రోమ్", "ક્રોમ", "ਕ੍ਰੋਮ", "ക്രോം", "ಕ್ರೋಮ್", "κρομ", "浏览器", "瀏覽器", "chrome"
            ) to "com.android.chrome",
            listOf(
                "ক্যামেরা", "कैमरा", "कॅमेरा", "کیمرہ", "كاميرا", "カメラ", "카메라", "камера", "камеру",
                "กล้อง", "கேமரா", "కెమెరా", "કેમેરા", "ਕੈਮਰਾ", "ക്യാമറ", "ಕ್ಯಾಮೆರಾ", "κάμερα", "相机", "相機",
                "cámara", "camara", "caméra", "appareil photo", "kamera", "máy ảnh", "fotocamera", "aparat", "camera"
            ) to "camera",
            listOf(
                "সেটিংস", "सेटिंग्स", "सेटिंग", "सेटिंग्ज", "سیٹنگز", "الإعدادات", "اعدادات", "設定", "설정",
                "настройки", "налаштування", "ตั้งค่า", "การตั้งค่า", "அமைப்புகள்", "సెట్టింగ్స్", "સેટિંગ્સ",
                "ਸੈਟਿੰਗਾਂ", "ക്രമീകരണങ്ങൾ", "ಸೆಟ್ಟಿಂಗ್ಸ್", "ρυθμίσεις", "设置", "設置",
                "ajustes", "configuración", "configuracion", "paramètres", "parametres", "réglages",
                "einstellungen", "configurações", "configuracoes", "ayarlar", "pengaturan", "setelan",
                "cài đặt", "impostazioni", "instellingen", "ustawienia", "inställningar", "settings"
            ) to "com.android.settings",
            listOf(
                "মেসেজ", "मैसेज", "मेसेज", "संदेश", "میسج", "پیغام", "رسائل", "رسالة", "メッセージ",
                "메시지", "문자", "сообщения", "смс", "повідомлення", "ข้อความ", "செய்திகள்", "మెసేజ్",
                "મેસેજ", "ਮੈਸੇਜ", "സന്ദേശങ്ങൾ", "ಮೆಸೇಜ್", "μηνύματα", "短信", "信息", "訊息",
                "mensajes", "nachrichten", "mensagens", "mesajlar", "pesan", "tin nhắn", "messaggi",
                "berichten", "wiadomości", "meddelanden", "sms", "messages"
            ) to "messages",
            listOf(
                "জিমেইল", "जीमेल", "جی میل", "جيميل", "ジーメール", "지메일", "джимейл", "จีเมล",
                "ஜிமெயில்", "జిమెయిల్", "જીમેલ", "ਜੀਮੇਲ", "ജിമെയിൽ", "ಜಿಮೇಲ್", "τζιμέιλ", "gmail"
            ) to "com.google.android.gm",
            listOf(
                "ম্যাপস", "मैप्स", "मॅप्स", "नक्शा", "میپس", "نقشہ", "خرائط", "マップ", "地図", "지도", "맵",
                "карты", "карти", "แผนที่", "மேப்ஸ்", "வரைபடம்", "మ్యాప్స్", "મેપ્સ", "ਮੈਪਸ", "മാപ്സ്", "ಮ್ಯಾಪ್ಸ್", "χάρτες", "地图", "地圖",
                "mapas", "cartes", "karten", "haritalar", "harita", "peta", "bản đồ", "mappe", "kaarten", "mapy", "kartor", "maps"
            ) to "com.google.android.apps.maps",
            listOf(
                "ফেসবুক", "फेसबुक", "فیس بک", "فيسبوك", "フェイスブック", "페이스북", "фейсбук",
                "เฟซบุ๊ก", "เฟสบุ๊ค", "ஃபேஸ்புக்", "ఫేస్బుక్", "ફેસબુક", "ਫੇਸਬੁੱਕ", "ഫേസ്ബുക്ക്", "ಫೇಸ್ಬುಕ್", "φέισμπουκ", "脸书", "臉書", "facebook"
            ) to "com.facebook.katana",
            listOf(
                "কল", "ফোন", "फोन", "فون", "هاتف", "تلفون", "電話", "전화", "телефон", "โทรศัพท์",
                "போன்", "ఫోన్", "ફોન", "ਫ਼ੋਨ", "ഫോൺ", "ಫೋನ್", "τηλέφωνο", "电话",
                "teléfono", "telefono", "téléphone", "telefon", "telepon", "điện thoại", "telefoon", "phone"
            ) to "phone",
            listOf(
                "গান", "মিউজিক", "गाना", "गीत", "संगीत", "म्यूजिक", "म्युझिक", "गाणं", "गाणे",
                "گانا", "موسیقی", "موسيقى", "أغنية", "اغنية", "اغاني", "音楽", "曲", "歌", "음악", "노래",
                "музыка", "музыку", "песня", "песню", "музика", "музику", "пісня", "пісню",
                "เพลง", "ดนตรี", "பாடல்", "பாட்டு", "இசை", "పాట", "సంగీతం", "ગીત", "સંગીત",
                "ਗਾਣਾ", "ਗੀਤ", "ਸੰਗੀਤ", "പാട്ട്", "സംഗീതം", "ಹಾಡು", "ಸಂಗೀತ", "μουσική", "τραγούδι", "音乐", "音樂", "歌曲",
                "música", "musica", "canción", "cancion", "musique", "chanson", "musik", "lied",
                "müzik", "muzik", "şarkı", "sarki", "lagu", "nhạc", "bài hát", "canzone",
                "muziek", "nummer", "muzyka", "muzykę", "piosenka", "piosenkę", "låt",
                "music", "song", "songs", "gaan", "gan"
            ) to "com.google.android.youtube"
        )

        private fun isLatinWordToken(token: String): Boolean =
            token.any { it.isLetter() } && token.all { ch ->
                ch.isWhitespace() || ch == '-' || ch == '\'' ||
                    Character.UnicodeBlock.of(ch) in setOf(
                        Character.UnicodeBlock.BASIC_LATIN,
                        Character.UnicodeBlock.LATIN_1_SUPPLEMENT,
                        Character.UnicodeBlock.LATIN_EXTENDED_A,
                        Character.UnicodeBlock.LATIN_EXTENDED_B,
                        Character.UnicodeBlock.LATIN_EXTENDED_ADDITIONAL
                    )
            }

        fun extractTargetApp(text: String): String? {
            val lower = text.trim().lowercase()
            if (lower.isEmpty()) return null
            for ((keywords, canonical) in TARGET_APP_CANONICAL_MAP) {
                for (keyword in keywords) {
                    val matched = if (isLatinWordToken(keyword)) {
                        Regex("""(?U)\b${Regex.escape(keyword)}""").containsMatchIn(lower)
                    } else {
                        lower.contains(keyword)
                    }
                    if (matched) return canonical
                }
            }
            return null
        }

        /** Capability-question markers ("korte paro", "can you", "পারো", plus equivalents across 30 languages): asks IF she can, does not command. */
        private val CAPABILITY_QUESTION = listOf(
            Regex("""\b(paro|paren|parbe|parbo|parben|pare|parish)\b""", RegexOption.IGNORE_CASE),
            Regex("""\b(can|could|will|would)\s+(you|u|mira)\b""", RegexOption.IGNORE_CASE),
            Regex("""\b(do|are)\s+you\s+(able|capable|control|operate)""", RegexOption.IGNORE_CASE),
            Regex("""\b(is\s+it\s+possible|able\s+to)\b""", RegexOption.IGNORE_CASE),
            Regex("পারো|পারে|পারবে|পারেন|পারবা"),
            // hi, mr, gu, pa
            Regex("सकते हो|सकती हो|सकते हैं|सकता है|क्या तुम|क्या आप"),
            Regex("शकतोस का|शकता का|करू शकतोस|उघडू शकतोस"),
            Regex("શકો છો|શકે છે|કરી શકો"),
            Regex("ਸਕਦੇ ਹੋ|ਸਕਦਾ ਹੈ|ਕਰ ਸਕਦੇ"),
            // ur, ar
            Regex("سکتے ہو|سکتی ہو|سکتے ہیں|کیا تم|کیا آپ"),
            Regex("هل يمكنك|هل تستطيع|هل تقدر"),
            // es, fr, de, pt, it, nl, pl, sv, tr, id, vi
            Regex("""(?U)\b(puedes|podrías|podrias|eres capaz de)\b""", RegexOption.IGNORE_CASE),
            Regex("""(?U)\b(peux-tu|peux tu|pouvez-vous|pouvez vous|tu peux|est-ce que tu peux)\b""", RegexOption.IGNORE_CASE),
            Regex("""(?U)\b(kannst du|können sie|koennen sie|könntest du)\b""", RegexOption.IGNORE_CASE),
            Regex("""(?U)\b(você pode|voce pode|podes|consegue|consegues)\b""", RegexOption.IGNORE_CASE),
            Regex("""(?U)\b(puoi|potresti|riesci a|sei in grado di)\b""", RegexOption.IGNORE_CASE),
            Regex("""(?U)\b(kun je|kan je|kunt u|zou je kunnen)\b""", RegexOption.IGNORE_CASE),
            Regex("""(?U)\b(czy możesz|czy mozesz|czy potrafisz)\b""", RegexOption.IGNORE_CASE),
            Regex("""(?U)\b(kan du|skulle du kunna)\b""", RegexOption.IGNORE_CASE),
            Regex("""(?U)\b(\p{L}+(?:abilir|ebilir)\s+m[iıuü]s[iıuü]n|yapabilir misin|açabilir misin)\b""", RegexOption.IGNORE_CASE),
            Regex("""(?U)\b(bisakah|apakah kamu bisa|bisa nggak|bisa tidak)\b""", RegexOption.IGNORE_CASE),
            Regex("""(?U)\b(bạn có thể|có thể .+ không|làm được không)\b""", RegexOption.IGNORE_CASE),
            // ru, uk, el
            Regex("""(?U)\b(ты можешь|можешь ли|вы можете|умеешь ли)\b""", RegexOption.IGNORE_CASE),
            Regex("""(?U)\b(ти можеш|чи можеш|ви можете|вмієш)\b""", RegexOption.IGNORE_CASE),
            Regex("""(?U)\b(μπορείς να|μπορεις να|θα μπορούσες)\b""", RegexOption.IGNORE_CASE),
            // ja, ko, zh, th, ta, te, ml, kn
            Regex("できますか|出来ますか|できる\\?|できる？|開けますか|再生できますか|可能ですか"),
            Regex("할 수 있|열 수 있|재생할 수 있|가능해|가능한가요"),
            Regex("你能.+[吗嗎]|你可以.+[吗嗎]|能不能|可不可以"),
            Regex("ได้ไหม|หรือเปล่า|สามารถ.+ไหม"),
            Regex("முடியுமா"),
            Regex("గలవా|గలరా|చేయగలవా|తెరవగలవా"),
            Regex("കഴിയുമോ|പറ്റുമോ"),
            Regex("ಸಾಧ್ಯವೇ|ಬಲ್ಲೆಯಾ|ಮಾಡಬಹುದೇ")
        )

        /** Concrete action verbs; a capability question containing one is still a (polite) command. */
        private val STRONG_ACTION = listOf(
            Regex("""\b(open|launch|start|play|message|text|call|search|find|show|turn\s+on|turn\s+off|set|take\s+a\s+photo)\b""", RegexOption.IGNORE_CASE),
            Regex("""\b(kholo|khulo|chalao|chalaw|pathao|pathaw|bajao|bajaw|dekhao|dekhaw)\b""", RegexOption.IGNORE_CASE),
            Regex("খুলো|খোলো|চালাও|পাঠাও|কল|খুঁজো|ওপেন|চালু|বাজাও|প্লে|শোনাও")
        )

        /** True for "can you control my phone"-style questions that carry no concrete action. */
        fun isCapabilityQuestion(text: String): Boolean {
            val lower = text.trim().lowercase()
            if (lower.isEmpty()) return false
            if (CAPABILITY_QUESTION.none { it.containsMatchIn(lower) }) return false
            return STRONG_ACTION.none { it.containsMatchIn(lower) }
        }

        fun isAutomationIntent(text: String): Boolean {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return false
            // A question about whether she CAN control the phone is answered by the Live model, never run as a goal.
            if (isCapabilityQuestion(trimmed)) return false
            val lower = trimmed.lowercase()

            // 1. Explicit pattern matches ("play <song> on youtube", "message <name> on whatsapp", "phone control")
            for (pattern in INTENT_PATTERNS) {
                if (pattern.containsMatchIn(lower)) return true
            }

            // 2. Automation verb check across all 30 languages:
            //    Latin-script verbs use Unicode word-boundary regex; non-Latin / no-word-boundary scripts use substring contains.
            val hasLatinVerb = LATIN_VERBS.any { verb ->
                if (verb.contains(" ")) {
                    Regex("""(?U)\b${Regex.escape(verb)}\b""").containsMatchIn(lower)
                } else {
                    Regex("""(?U)\b${Regex.escape(verb)}\b""").containsMatchIn(lower)
                }
            }
            val hasSubstringVerb = SUBSTRING_VERBS.any { lower.contains(it) }
            val hasVerb = hasLatinVerb || hasSubstringVerb

            if (!hasVerb) return false

            // 3. Target app/media noun check (prefix word boundary for Latin tokens to support suffixes like -e, -এ, -te)
            val hasApp = KNOWN_APPS.any { app ->
                if (isLatinWordToken(app)) {
                    Regex("""(?U)\b${Regex.escape(app)}""").containsMatchIn(lower)
                } else {
                    lower.contains(app)
                }
            }

            return hasApp
        }
    }
}
