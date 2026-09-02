# BT-Chat — צ'אט דרך בלוטוס (Android ⇄ Windows)

אפליקציית Android (Kotlin) ותוכנת Windows (WPF/.NET) שמדברות ביניהן ישירות דרך
**Bluetooth Classic (SPP)** — כולל טקסט וצירוף קבצים, בכל שילוב: אנדרואיד⇄אנדרואיד,
ווינדוס⇄ווינדוס, אנדרואיד⇄ווינדוס. הפרוטוקול המשותף מתועד ב-[`PROTOCOL.md`](PROTOCOL.md).

## מבנה הריפו
```
BluetoothChat/
├── android/                        # אפליקציית Android (Gradle/Kotlin)
│   └── app/keystore/release.jks    # מפתח חתימה קבוע (מוטמע בריפו!)
├── windows/BtChatWpf/              # תוכנת Windows (C#/WPF, 32feet.NET)
├── .github/workflows/build-all.yml # workflow יחיד שבונה גם אנדרואיד וגם ווינדוס
└── PROTOCOL.md
```

## התקנה - בלי שום שלב ידני

1. חילצו את ה-zip.
2. בריפו שלכם ב-GitHub: **Add file → Upload files**, וגררו לתוך תיבת ההעלאה את
   **כל התוכן** של התיקייה המחולצת (את התיקיות `android`, `windows`, `.github`
   ואת הקבצים הבודדים כמו `README.md`, `PROTOCOL.md`, `.gitignore`) - לא את
   התיקייה `BluetoothChat` עצמה, אלא מה שבתוכה.
   (אם כבר יש קבצים קיימים מנסיון קודם עם אותם שמות, ההעלאה פשוט תחליף אותם.)
3. גוללים למטה ולוחצים **Commit changes**.

זהו. **אין עוד שום שלב ידני.** ה-commit הזה עצמו כבר מפעיל את ה-workflow
(`build-all.yml`) אוטומטית, כי הוא רשום ב-`on: push` - אין צורך ללחוץ שום כפתור
"Run workflow" בפעם הראשונה או בפעמים הבאות.

## איפה מקבלים את הקבצים המוכנים

1. למעלה בתפריט הראשי של הריפו → **Actions**.
2. נכנסים ל-run האחרון של **"Build Everything (Android + Windows)"**
   (אם אין run עדיין, ה-push שלכם עוד בדרך - חכו כדקה ורעננו את הדף).
3. בתוך ה-run, למטה תחת **Artifacts**, תראו שני קבצים:
   - `BtChat-android-apk` → מכיל `app-release.apk`
   - `BtChat-windows-exe` → מכיל `BtChat.exe`
4. לוחצים על כל אחד כדי להוריד (הם ZIP קטן שמכיל את הקובץ עצמו).

## עדכונים עתידיים

בכל פעם שתעדכנו קוד ותעלו שוב (Upload files → Commit), ה-workflow ירוץ אוטומטית
מחדש ויפיק APK/EXE חדשים - **חתומים באותו מפתח בדיוק** (`keystore/release.jks`
שכבר נמצא בריפו), כך שמתקינים את ה-APK החדש ישירות מעל הישן באנדרואיד בלי
צורך להסיר קודם.

## איך משתמשים באפליקציות עצמן
1. **מזווגים (Pair)** בין שני המכשירים דרך הגדרות הבלוטוס של המערכת (Windows
   Settings / Android Settings) — לפני פתיחת האפליקציה.
2. בצד אחד לוחצים **"האזן לחיבורים"**.
3. בצד השני לוחצים **"התחבר למכשיר"** ובוחרים מהרשימה של המכשירים המזווגים.
4. לאחר החיבור אפשר לשלוח טקסט או ללחוץ 📎 לצירוף קובץ.

קבצים שמתקבלים נשמרים: ב-Android תחת `Android/data/com.btchat.app/files/Downloads`,
ב-Windows תחת `Documents\BtChat received`.

## הערה על אבטחה
מפתח החתימה (`keystore/release.jks`) מוטמע בריפו כדי שהכל יעבוד בלי הגדרה
ידנית - זה בסדר גמור לריפו **פרטי** לשימוש אישי (כמו זה שלכם). אם אי-פעם
תהפכו את הריפו לציבורי, כדאי להעביר את הסיסמה/המפתח ל-GitHub Secrets במקום
(שוב, זה יידרש שינוי ידני חד-פעמי אז - לא עכשיו).

## הערות טכניות
- **Windows**: 32feet.NET (`InTheHand.Net.Bluetooth`) - Win32 Bluetooth קלאסי, לא
  דורש אריזת MSIX.
- **Android**: דורש הרשאות `BLUETOOTH_CONNECT`/`BLUETOOTH_SCAN` (Android 12+),
  נשאלות אוטומטית באפליקציה.
- חיבור 1-to-1 בלבד (לא תמיכה בכמה מכשירים בו-זמנית), לפי הבחירה שסיכמנו.
