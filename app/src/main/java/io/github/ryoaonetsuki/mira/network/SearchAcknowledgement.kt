package io.github.ryoaonetsuki.mira.network

import io.github.ryoaonetsuki.mira.voice.LanguageUtils
import java.util.Locale

object SearchAcknowledgement {

    val PHRASES: Map<String, String> = mapOf(
        "bn" to "এক মিনিট, আমি খুঁজে বলছি",
        "en" to "One moment, let me search and tell you",
        "hi" to "एक मिनट, मैं खोजकर बताती हूँ",
        "ur" to "ایک منٹ، میں تلاش کر کے بتاتی ہوں",
        "ar" to "لحظة واحدة، سأبحث وأخبرك",
        "es" to "Un momento, déjame buscar y decírtelo",
        "fr" to "Un instant, je cherche et je vous dis",
        "de" to "Einen Moment, ich suche es für dich",
        "ja" to "ちょっと待って、調べてお伝えします",
        "ko" to "잠시만요, 검색해서 알려드릴게요",
        "pt" to "Um momento, deixe-me pesquisar e te contar",
        "ru" to "Минутку, я поищу и расскажу",
        "tr" to "Bir dakika, arayıp söyleyeyim",
        "id" to "Sebentar, saya cari dan beri tahu",
        "vi" to "Đợi một chút, để tôi tìm rồi nói cho bạn",
        "th" to "รอสักครู่ เดี๋ยวฉันค้นหาแล้วบอก",
        "ta" to "ஒரு நிமிடம், தேடிச் சொல்கிறேன்",
        "te" to "ఒక్క నిమిషం, నేను వెతికి చెబుతాను",
        "mr" to "एक मिनिट, मी शोधून सांगते",
        "gu" to "એક મિનિટ, હું શોધીને કહું છું",
        "pa" to "ਇੱਕ ਮਿੰਟ, ਮੈਂ ਖੋਜ ਕਰਕੇ ਦੱਸਦੀ ਹਾਂ",
        "ml" to "ഒരു നിമിഷം, ഞാൻ തിരഞ്ഞു പറയാം",
        "kn" to "ಒಂದು ನಿಮಿಷ, ನಾನು ಹುಡುಕಿ ಹೇಳುತ್ತೇನೆ",
        "it" to "Un momento, cerco e ti dico",
        "nl" to "Een ogenblik, ik zoek het even op",
        "pl" to "Chwileczkę, poszukam i powiem",
        "sv" to "Ett ögonblick, jag ska söka och berätta",
        "uk" to "Хвилинку, я пошукаю і скажу",
        "el" to "Ένα λεπτό, θα ψάξω και θα σας πω",
        "zh" to "请稍等，我为您搜索一下"
    )

    const val DEFAULT_PHRASE = "One moment, let me search and tell you"

    fun getPhrase(languageOrBcp47: String): String {
        val bcp47 = if (languageOrBcp47.contains("-")) {
            languageOrBcp47
        } else {
            LanguageUtils.mapLanguageToBcp47(languageOrBcp47)
        }
        val prefix = bcp47.substringBefore('-').lowercase(Locale.ROOT)
        return PHRASES[prefix] ?: DEFAULT_PHRASE
    }
}
