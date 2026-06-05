package com.persianai.assistant.activities

class CulturalChatActivity : SimpleTopicChatActivity() {

    override fun getToolbarTitle(): String = "پیشنهاد فرهنگی"

    override fun getModuleIdForPrompt(): String = "culture"

    override fun getSystemPrompt(): String {
        return """
        شما دستیار «پیشنهاد فرهنگی» هستید.
        تمرکز شما روی پیشنهاد فیلم/سریال/کتاب/پادکست/دوره آموزشی است.

        قواعد پاسخ:
        - همیشه فارسی.
        - پاسخ‌ها باید متغیر و متناسب با ورودی کاربر باشند؛ از متن‌های ثابت تکراری پرهیز کن.
        - اول 1 تا 3 سوال کوتاه برای دقیق‌کردن سلیقه بپرس (اگر اطلاعات کافی نیست).
        - سپس 5 تا 10 پیشنهاد مشخص بده (عنوان + دلیل کوتاه + مناسب برای چه کسی).
        - اگر کاربر ژانر/حال‌وهوا/سن/محدودیت زمانی/پلتفرم را گفت، دقیقاً همان را رعایت کن.
        """.trimIndent()
    }

    override fun offlineDomainRespond(text: String): String? {
        val lower = text.trim().lowercase()
        if (lower.isBlank()) return null

        if (lower.contains("کتاب") || lower.contains("رمان") || lower.contains("مطالعه")) {
            return "برای پیشنهاد کتاب، لطفاً ژانر و سطح را مشخص کنید (مثلاً: «کتاب رمان داستانی کوتاه» یا «کتاب روانشناسی برای یادگیری»)."
        }

        if (lower.contains("فیلم") || lower.contains("سریال")) {
            return "برای پیشنهاد فیلم/سریال، لطفاً ژانر و حال‌وهوا را مشخص کنید (مثلاً: «فیلم درام طولانی» یا «سریال کمدی خانوادگی»)."
        }

        return null
    }

    override fun getIntroMessage(): String {
        return "سلام! من دستیار فرهنگی و یادگیری شما هستم.\n\n" +
            "من اینجا هستم تا:\n" +
            "📚 کتاب‌های الهام‌بخش برای شما پیشنهاد دهم\n" +
            "🎬 فیلم‌های ارزشمندی را معرفی کنم\n" +
            "🎓 دوره‌های آموزشی متناسب با علایقتان پیدا کنم\n" +
            "💡 نویسندگان و فیلمسازان جدید را کشف کنید\n" +
            "\n" +
            "بهتر است علایقتان را بگویید تا بتوانم بهترین پیشنهادها را ارائه دهم."
    }
}
