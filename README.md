# 📱 دستیار هوش مصنوعی فارسی - نسخه 2.0

<div align="center">

![Version](https://img.shields.io/badge/version-2.0-blue)
![Platform](https://img.shields.io/badge/platform-Android-green)
![Language](https://img.shields.io/badge/language-Kotlin-purple)
![License](https://img.shields.io/badge/license-MIT-orange)

**دستیار هوشمند فارسی با قابلیت‌های پیشرفته مالی و یادآوری**

</div>

---

## ✨ قابلیت‌های اصلی

### 💳 مدیریت مالی
- ✅ **مدیریت چک‌ها**: ثبت و پیگیری چک‌های پرداختی/دریافتی
- ✅ **مدیریت اقساط**: مدیریت کامل اقساط (وام، خرید، اجاره)
- ✅ **هشدارهای هوشمند**: یادآوری خودکار سررسیدها
- ✅ **گزارش‌گیری**: آمار و نمودارهای کامل

### 📝 یادآوری‌های پیشرفته
- ✅ **یادآوری زمانی**: با تقویم فارسی
- 🚧 **یادآوری مکانی**: براساس GPS (در حال توسعه)
- 🚧 **یادآوری تکراری**: روزانه، هفتگی، ماهانه (در حال توسعه)
- 🚧 **یادآوری شرطی**: براساس شرایط (در حال توسعه)
- ✅ **اولویت‌بندی**: کم، متوسط، بالا
- ✅ **دسته‌بندی**: شخصی، کاری، خانوادگی، و...

### 🤖 هوش مصنوعی
- ✅ چت با مدل‌های GPT-4 و Claude
- ✅ تشخیص صوت فارسی (Whisper)
- ✅ پردازش زبان طبیعی فارسی
- ✅ دستورات صوتی

---

## 📦 نصب و راه‌اندازی

### پیش‌نیازها
- Android Studio Arctic Fox یا بالاتر
- JDK 11 یا بالاتر
- Android SDK 24 یا بالاتر

### مراحل نصب

1. **Clone کردن پروژه**
```bash
git clone https://github.com/ghadirb/PersianAIAssistantOnline.git
cd PersianAIAssistantOnline
```

2. **Build Project**
```bash
./gradlew clean
./gradlew assembleDebug
```

3. **نصب روی دستگاه**
```bash
./gradlew installDebug
```

---

## 🚀 Push به GitHub

برای ارسال تغییرات به GitHub، یکی از این دو روش را استفاده کنید:

### روش 1: اسکریپت BAT (ویندوز)
```bash
git_push.bat
```

### روش 2: اسکریپت Python
```bash
python git_push.py
```

### روش 3: دستی
```bash
git add .
git commit -m "توضیحات تغییرات"
git push origin main
```

---

## 📚 مستندات

- **[IMPLEMENTATION_PLAN_FIXED.md](IMPLEMENTATION_PLAN_FIXED.md)** - پلن جامع 12 فازی
- **[CHANGES_AND_GUIDE.md](CHANGES_AND_GUIDE.md)** - راهنمای کامل تغییرات
- **[CHANGELOG.md](CHANGELOG.md)** - لیست تغییرات نسخه 2.0
- **[SUMMARY.md](SUMMARY.md)** - خلاصه کارها

---

## 🏗️ ساختار پروژه

```
PersianAIAssistantOnline/
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/com/persianai/assistant/
│   │   │   │   ├── activities/
│   │   │   │   │   ├── MainActivity.kt
│   │   │   │   │   ├── ChecksManagementActivity.kt ✨ جدید
│   │   │   │   │   ├── InstallmentsManagementActivity.kt ✨ جدید
│   │   │   │   │   └── AdvancedRemindersActivity.kt ✨ جدید
│   │   │   │   ├── adapters/
│   │   │   │   │   └── RemindersAdapter.kt ✨ جدید
│   │   │   │   ├── data/
│   │   │   │   │   ├── Check.kt
│   │   │   │   │   ├── Installment.kt
│   │   │   │   │   └── AdvancedReminder.kt ✨ جدید
│   │   │   │   └── ...
│   │   │   └── res/
│   │   │       ├── layout/
│   │   │       │   ├── activity_advanced_reminders.xml ✨ جدید
│   │   │       │   ├── item_reminder.xml ✨ جدید
│   │   │       │   └── dialog_time_reminder.xml ✨ جدید
│   │   │       └── ...
│   └── build.gradle
├── CHANGELOG.md ✨ جدید
├── IMPLEMENTATION_PLAN_FIXED.md ✨ جدید
├── CHANGES_AND_GUIDE.md ✨ جدید
├── git_push.bat ✨ جدید
└── git_push.py ✨ جدید
```

---

## 🎯 Roadmap

### ✅ نسخه 2.0 (فعلی)
- [x] مدیریت چک‌ها
- [x] مدیریت اقساط
- [x] یادآوری‌های پیشرفته (زمانی)
- [x] مستندات کامل

### 🔜 نسخه 2.1 (هفته آینده)
- [ ] یادآوری مکانی (GPS)
- [ ] یادآوری تکراری
- [ ] یادآوری شرطی
- [ ] مدیریت خودرو

### 🔮 نسخه 2.2 (2 هفته آینده)
- [ ] دستیار سفر
- [ ] ماژول خانواده
- [ ] مدیریت اسناد
- [ ] بهبود UI/UX

### 🚀 نسخه 3.0 (آینده)
- [ ] بازسازی موزیک پلیر
- [ ] بازسازی مسیریابی
- [ ] بازسازی آب و هوا
- [ ] دستیار مکالمه‌ای کامل

---

## 🤝 مشارکت

برای مشارکت در این پروژه:

1. Fork کنید
2. یک Branch جدید بسازید (`git checkout -b feature/AmazingFeature`)
3. تغییرات را Commit کنید (`git commit -m 'Add some AmazingFeature'`)
4. Push کنید (`git push origin feature/AmazingFeature`)
5. یک Pull Request باز کنید

---

## 📝 لایسنس

این پروژه تحت لایسنس MIT منتشر شده است.

---

## 📞 ارتباط

- **GitHub**: https://github.com/ghadirb/PersianAIAssistantOnline
- **Issues**: برای گزارش باگ یا درخواست قابلیت جدید

---

## 🙏 تشکر

از همه کسانی که در توسعه این پروژه مشارکت داشته‌اند، تشکر می‌کنیم.

---

<div align="center">

**ساخته شده با ❤️ در ایران**

</div>
