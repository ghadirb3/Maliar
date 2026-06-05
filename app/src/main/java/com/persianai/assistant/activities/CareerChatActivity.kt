package com.persianai.assistant.activities

class CareerChatActivity : SimpleTopicChatActivity() {

    override fun getToolbarTitle(): String = "مشاور مسیر شغلی"

    override fun getModuleIdForPrompt(): String = "career"

    override fun getSystemPrompt(): String {
        return """
        شما دستیار «مشاور مسیر شغلی/تحصیلی» هستید.
        هدف: کمک برای انتخاب مسیر، برنامه یادگیری، رزومه/CV، آمادگی مصاحبه و تصمیم‌گیری شغلی.

        قواعد پاسخ:
        - همیشه فارسی.
        - پاسخ‌ها باید متناسب با شرایط کاربر باشد و از متن ثابت تکراری پرهیز شود.
        - اگر اطلاعات کافی نیست، سوالات دقیق بپرس: سن/سابقه/مهارت‌ها/هدف/زمان آزاد/محدودیت‌ها.
        - خروجی‌ها را ساختارمند بده: جدول/لیست مرحله‌ای/برنامه هفتگی.
        - اگر کاربر رزومه می‌خواهد: اول اطلاعات لازم را جمع کن و سپس قالب بخش‌ها + مثال bullet ارائه بده.
        """.trimIndent()
    }

    override fun offlineDomainRespond(text: String): String? {
        val lower = text.trim().lowercase()
        if (lower.isBlank()) return null

        if (lower.contains("رزوم") || lower.contains("cv") || lower.contains("مصاحبه")) {
            return "برای راهنمایی درباره رزومه/مصاحبه، لطفاً شغل مورد علاقه و سابقه‌ای که دارید را بگویید."
        }

        if (lower.contains("برنامه") || lower.contains("مسیر") || lower.contains("یادگیری") || lower.contains("مهارت")) {
            return "برای طراحی مسیر شغلی، لطفاً حوزه مورد علاقه، سطح فعلی و هدف‌های شخصی‌تان را مشخص کنید."
        }

        return null
    }

    override fun getIntroMessage(): String {
        return "سلام! من مشاور مسیر شغلی شما هستم.\n\n" +
            "من اینجا هستم تا:\n" +
            "🎯 بر اساس علایق و مهارت‌های شما بهترین راه‌حل را پیدا کنم\n" +
            "💼 درباره شغل‌ها و رشته‌های مختلف اطلاعات دهم\n" +
            "🚀 برنامه‌ای برای توسعه مهارت‌های شما بسازم\n\n" +
            "⚠️ توجه: نتایج این مشاوره تنها نقش راهنمایی دارد. تصمیم نهایی با شماست و بهتر است با یک مشاور حرفه‌ای نیز مشورت کنید.\n\n" +
            "اولاً، مسیری رو انتخاب کن که علاقه‌مند هستی: آموزش، شغل، یا تغییر مسیر موجود؟"
    }
}
