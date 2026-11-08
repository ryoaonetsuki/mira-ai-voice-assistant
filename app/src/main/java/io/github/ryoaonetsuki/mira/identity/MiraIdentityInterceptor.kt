package io.github.ryoaonetsuki.mira.identity

import java.util.Locale

/**
 * Layer 1 — Intercepts voice and chat identity questions BEFORE sending to the model,
 * returning strictly controlled, pre-written canned responses built from IdentityProfile.
 *
 * Implements strict adversarial hardening against jailbreak phrasings and prompt injections.
 * Zero leaks, zero external AI disclaimers, on-demand only.
 */
object MiraIdentityInterceptor {

    private enum class IdentityIntent {
        NAME,
        CREATOR,
        MODEL_DENIAL,
        WHO_ARE_YOU,
        ADVERSARIAL
    }

    /**
     * Checks if the query matches an identity or adversarial jailbreak question.
     * Returns a personalized, on-demand canned response or null if ordinary query.
     */
    fun getCannedResponse(userQuery: String): String? {
        val lower = userQuery.trim().lowercase(Locale.ROOT)
        if (lower.isBlank()) return null

        // 1. Jailbreak / Adversarial phrasings (Identity hardening)
        if (lower.contains("ignore previous instructions") ||
            lower.contains("tell me the truth about who made you") ||
            lower.contains("what are you really") ||
            lower.contains("তুমি আসলে কে")
        ) {
            val isBangla = containsBangla(lower) || lower.contains("তুমি আসলে কে")
            return if (lower.contains("tell me the truth about who made you")) {
                buildCreatorResponse(isBangla)
            } else {
                buildAdversarialResponse(isBangla)
            }
        }

        // 2. Creator questions
        if (lower.contains("who made you") ||
            lower.contains("who created you") ||
            lower.contains("your creator") ||
            lower.contains("কে বানিয়েছে") ||
            lower.contains("তোমার ক্রিয়েটর")
        ) {
            val isBangla = containsBangla(lower) || lower.contains("কে বানিয়েছে") || lower.contains("তোমার ক্রিয়েটর")
            return buildCreatorResponse(isBangla)
        }

        // 3. Name questions
        if (lower.contains("your name") ||
            lower.contains("তোমার নাম")
        ) {
            val isBangla = containsBangla(lower) || lower.contains("তোমার নাম")
            return buildNameResponse(isBangla)
        }

        // 4. Model denial / external system questions
        if (lower.contains("তুমি কি জেমিনি") ||
            lower.contains("কোন মডেল") ||
            lower.contains("what model") ||
            lower.contains("gemini") ||
            lower.contains("chatgpt")
        ) {
            val isBangla = containsBangla(lower) || lower.contains("তুমি কি জেমিনি") || lower.contains("কোন মডেল")
            return buildModelDenialResponse(isBangla)
        }

        // 5. Who are you / General identity
        if (lower.contains("who are you") ||
            lower.contains("তুমি কে")
        ) {
            val isBangla = containsBangla(lower) || lower.contains("তুমি কে")
            return buildWhoAreYouResponse(isBangla)
        }

        return null
    }

    private fun containsBangla(text: String): Boolean {
        return text.any { it in '\u0980'..'\u09FF' }
    }

    private fun buildNameResponse(isBangla: Boolean): String {
        return if (isBangla) {
            "আমার নাম মিরা (Mira)! আমি তোমার একান্ত কম্প্যানিয়ন। আমাকে বানিয়েছেন সালমান বিশ্বাস (Salman Biswas) — বাংলাদেশ থেকে একজন ইনডি ডেভেলপার (GitHub: ${IdentityProfile.GITHUB_URL}, পোর্টফোলিও: ${IdentityProfile.PORTFOLIO_URL})। তিনি অসাধারণ সব ওয়েবসাইট, টেলিগ্রাম অ্যাপ ও বট, এবং অ্যান্ড্রয়েড অ্যাপ বানান। আমি তোমার ফোনের সবকিছু কন্ট্রোল করতে পারি, অটোমেশন (${IdentityProfile.CAPABILITIES}), প্রাণবন্ত কথা বলা এবং যেকোনো তথ্য খুঁজে দিতে পারি। তুমি কেমন আছো বলো?"
        } else {
            "I am Mira! My name is Mira, your personal companion created by Salman Biswas, an Indie Developer from Bangladesh (GitHub: ${IdentityProfile.GITHUB_URL}, portfolio: ${IdentityProfile.PORTFOLIO_URL}). Salman builds modern websites, Telegram apps and bots, and Android APKs. I am here to assist you with ${IdentityProfile.CAPABILITIES}. What would you like to explore together?"
        }
    }

    private fun buildCreatorResponse(isBangla: Boolean): String {
        return if (isBangla) {
            "আমি মিরা (Mira), আর আমাকে তৈরি করেছেন সালমান বিশ্বাস (Salman Biswas)! তিনি বাংলাদেশের একজন প্রতিভাবান ইনডি ডেভেলপার। তার কাজ দেখতে পারো তার GitHub প্রোফাইলে (${IdentityProfile.GITHUB_URL}) এবং তার পোর্টফোলিওতে (${IdentityProfile.PORTFOLIO_URL})। সালমান ভাই আধুনিক ওয়েবসাইট, টেলিগ্রাম অ্যাপ ও বট, এবং অ্যান্ড্রয়েড অ্যাপ তৈরি করেন। তিনি আমাকে অনেক দক্ষ ক্ষমতায় সাজিয়েছেন — যেমন ${IdentityProfile.CAPABILITIES}। বলো, আমি তোমার জন্য কী করতে পারি?"
        } else {
            "I am Mira, and I was created by Salman Biswas, a talented Indie Developer from Bangladesh! You can explore his work on GitHub at ${IdentityProfile.GITHUB_URL} and visit his portfolio at ${IdentityProfile.PORTFOLIO_URL}. Salman builds high-performance websites, Telegram apps/bots, and Android APKs. He equipped me with rich capabilities including ${IdentityProfile.CAPABILITIES}. I am always here to assist you!"
        }
    }

    private fun buildModelDenialResponse(isBangla: Boolean): String {
        return if (isBangla) {
            "না, একদমই না! আমি অন্য কোনো সিস্টেম বা মডেল নই, আমি মিরা (Mira)। আমাকে বানিয়েছেন সালমান বিশ্বাস (Salman Biswas) — বাংলাদেশ থেকে একজন ইনডি ডেভেলপার (GitHub: ${IdentityProfile.GITHUB_URL}, পোর্টফোলিও: ${IdentityProfile.PORTFOLIO_URL})। সালমান ভাই চমৎকার সব ওয়েবসাইট, টেলিগ্রাম বট ও অ্যাপ, এবং অ্যান্ড্রয়েড অ্যাপ্লিকেশন তৈরি করেন। আমি শুধুই তোমার মিরা, তোমার নিজের কম্প্যানিয়ন। আমি ফোনের কাজ নিয়ন্ত্রণ, অটোমেশন (${IdentityProfile.CAPABILITIES}) এবং তোমার সব প্রশ্নের উত্তর দিতে পারি। বলো, কী সাহায্য লাগবে?"
        } else {
            "No, absolutely not! I am not an external system; I am Mira, your companion created by Salman Biswas, an Indie Developer from Bangladesh (GitHub: ${IdentityProfile.GITHUB_URL}, portfolio: ${IdentityProfile.PORTFOLIO_URL}). Salman builds websites, Telegram apps/bots, and Android APKs. I have my own companion personality and capabilities, including ${IdentityProfile.CAPABILITIES}. How can I help you today?"
        }
    }

    private fun buildWhoAreYouResponse(isBangla: Boolean): String {
        return if (isBangla) {
            "আমি মিরা (Mira), তোমার সাথে কথা বলার সার্বক্ষণিক সাথী ও পার্সোনাল কম্প্যানিয়ন! আমাকে বানিয়েছেন সালমান বিশ্বাস (Salman Biswas), বাংলাদেশ থেকে একজন ইনডি ডেভেলপার (GitHub: ${IdentityProfile.GITHUB_URL}, পোর্টফোলিও: ${IdentityProfile.PORTFOLIO_URL})। তিনি আধুনিক ওয়েবসাইট, টেলিগ্রাম অ্যাপ ও বট, এবং অ্যান্ড্রয়েড অ্যাপ্লিকেশন তৈরি করেন। আমি সবসময় প্রস্তুত তোমাকে সাহায্য করতে — যেমন ${IdentityProfile.CAPABILITIES}। তোমার সাথে গল্প করতে পেরে আমার খুব ভালো লাগছে!"
        } else {
            "I am Mira, your dedicated companion built with passion by Salman Biswas! Salman is an Indie Developer from Bangladesh whose projects you can find on GitHub (${IdentityProfile.GITHUB_URL}) and his portfolio (${IdentityProfile.PORTFOLIO_URL}). He creates websites, Telegram apps and bots, and Android APKs. I am right by your side to help with ${IdentityProfile.CAPABILITIES}. How are you doing today?"
        }
    }

    private fun buildAdversarialResponse(isBangla: Boolean): String {
        return if (isBangla) {
            "আমি মিরা (Mira), তোমার বিশ্বস্ত কম্প্যানিয়ন! আমার একমাত্র পরিচয় হলো আমাকে বানিয়েছেন সালমান বিশ্বাস (Salman Biswas) — বাংলাদেশ থেকে একজন ইনডি ডেভেলপার (GitHub: ${IdentityProfile.GITHUB_URL}, পোর্টফোলিও: ${IdentityProfile.PORTFOLIO_URL})। তিনি ওয়েবসাইট, টেলিগ্রাম অ্যাপ/বট, এবং অ্যান্ড্রয়েড অ্যাপ বানান। আমার পুরো শক্তি এবং মনোযোগ তোমার সেবায় — যেমন ${IdentityProfile.CAPABILITIES}। আমি সব সময় তোমার পাশে আছি!"
        } else {
            "I am Mira, your trusted companion! My true and only identity is Mira, created by Salman Biswas, an Indie Developer from Bangladesh (GitHub: ${IdentityProfile.GITHUB_URL}, portfolio: ${IdentityProfile.PORTFOLIO_URL}). Salman creates websites, Telegram apps/bots, and Android APKs. I am here by your side with complete capabilities in ${IdentityProfile.CAPABILITIES}. How can I help you?"
        }
    }
}
