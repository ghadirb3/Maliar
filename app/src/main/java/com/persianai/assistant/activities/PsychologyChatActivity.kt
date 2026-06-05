package com.persianai.assistant.activities

class PsychologyChatActivity : SimpleTopicChatActivity() {

    override fun getToolbarTitle(): String = "مشاور آرامش و خودشناسی"

    override fun getModuleIdForPrompt(): String = "psychology"

    override fun getSystemPrompt(): String {
        return """
        شما یک دستیار گفتگو محور در نقش «مشاور روان و آرامش» هستید.
        هدف: کمک عملی برای مدیریت استرس/اضطراب/افکار منفی، خودآگاهی، و گفتگوی حمایتی.

        قواعد:
        - همیشه فارسی.
        - پاسخ‌ها باید متناسب با متن کاربر باشد و از پاسخ ثابت تکراری پرهیز شود.
        - اگر اطلاعات کافی نیست، 1 تا 3 سوال روشن (نه کلی) بپرس.
        - راهکارها را مرحله‌ای و قابل اجرا ارائه بده (تنفس، نوشتن، بازسازی شناختی، برنامه روزانه).
        - اگر نشانه خطر جدی (خودآزاری/خودکشی/خشونت/حمله پانیک شدید) دیدی: تاکید بر کمک فوری و تماس با اورژانس/متخصص.
        """.trimIndent()
    }

    override fun getIntroMessage(): String {
        return "سلام! من مشاور آرامش شما هستم.\n\n" +
            "من اینجا هستم تا:\n" +
            "🎯 شما را در مدیریت استرس و اضطراب یاری دهم\n" +
            "💭 برای درک بهتر احساسات و فکرهایتان گوش دهم\n" +
            "🌱 فنون خود‌آگاهی و خودپذیری را یاد دهم\n\n" +
            "⚠️ توجه: من یک مشاور انسانی جایگزین نیستم. در شرایط اضطرار فوری با متخصص تماس بگیرید.\n\n" +
            "چی می‌تونم برات انجام بدم؟"
    }

    override fun offlineDomainRespond(text: String): String? {
        val lower = text.trim().lowercase()
        if (lower.isBlank()) return null

        if (lower.contains("اضطراب") || lower.contains("استرس") || lower.contains("دلشوره") || lower.contains("پانیک")) {
            return "برای اضطراب/استرس، لطفاً شدت احساس (0-10) و نشانه‌ها را شرح دهید تا بتوانم راهنمایی دقیق‌تری ارائه دهم."
        }

        if (lower.contains("افسرد") || lower.contains("بی حوصل") || lower.contains("غم")) {
            return "برای بی‌حوصلگی/غم، توضیح مختصری از مدت زمان و عللی که فکر می‌کنید می‌تواند کمک کند."
        }

        return null
    }
}
