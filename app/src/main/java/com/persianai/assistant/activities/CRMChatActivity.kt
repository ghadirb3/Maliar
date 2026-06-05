package com.persianai.assistant.activities

class CRMChatActivity : SimpleTopicChatActivity() {

    override fun getToolbarTitle(): String = "دفتر مشتریان"

    override fun getModuleIdForPrompt(): String = "crm"

    override fun getSystemPrompt(): String {
        return """
        شما دستیار «دفتر مشتریان» (CRM) هستید.
        هدف: مدیریت مشتریان، تماس‌ها، پیگیری‌های فروش، یادداشت‌ها و مراحل کار (Pipeline).

        قواعد:
        - همیشه فارسی.
        - پاسخ‌ها باید متناسب با درخواست کاربر باشند و از متن‌های تکراری پرهیز شود.
        - اگر اطلاعات کافی نیست، سوالات مشخص بپرس: نام مشتری، نوع محصول/خدمت، مرحله فروش، زمان تماس قبلی.
        - خروجی‌ها را ساختارمند بده: جدول، لیست گام‌های بعدی، یادآوری‌های مهم.
        - در صورت نیاز، برای پیگیری‌های بعدی اطلاعات مختصری ثبت کن.
        """.trimIndent()
    }

    override fun offlineDomainRespond(text: String): String? {
        val lower = text.trim().lowercase()
        if (lower.isBlank()) return null

        if (lower.contains("مشتری") || lower.contains("مشتریان") || lower.contains("client")) {
            return "برای مدیریت مشتری، لطفاً نام مشتری و نوع محصول/خدمت مورد نظر را مشخص کنید."
        }

        if (lower.contains("فروش") || lower.contains("پیگیری") || lower.contains("follow")) {
            return "برای پیگیری فروش، لطفاً مرحله فعلی (چاپ، منتظر تأیید، بسته‌شده) و تاریخ تماس آخر را بگویید."
        }

        if (lower.contains("یادداشت") || lower.contains("note")) {
            return "برای افزودن یادداشت مشتری، لطفاً نام مشتری و متن یادداشت را وارد کنید."
        }

        return null
    }

    override fun getIntroMessage(): String {
        return "سلام! من دستیار دفتر مشتریان شما هستم.\n\n" +
            "من اینجا هستم تا:\n" +
            "👥 مشتریان و اطلاعات آن‌ها را ثبت کنیم\n" +
            "📞 تماس‌ها و پیگیری‌های شما را مدیریت کنم\n" +
            "📝 یادداشت‌ها و یادآوری‌های مهم را حفظ کنم\n" +
            "📊 خلاصه‌ای از روند فروش و مراحل کار را دنبال کنم\n\n" +
            "چی می‌تونم برات انجام بدم؟ (مثل افزودن مشتری، ایجاد جدول، خلاصه‌سازی...)"
    }
}
