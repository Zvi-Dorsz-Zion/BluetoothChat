# BT-Chat — צ'אט דרך בלוטוס (Android ⇄ Windows)

אפליקציית Android (Kotlin) ותוכנת Windows (WPF/.NET) שמדברות ביניהן ישירות דרך
**Bluetooth Classic (SPP)** — כולל טקסט וצירוף קבצים, בכל שילוב: אנדרואיד⇄אנדרואיד,
ווינדוס⇄ווינדוס, אנדרואיד⇄ווינדוס. שני הצדדים בנויים לפי אותו פרוטוקול — ראו
[`PROTOCOL.md`](PROTOCOL.md).

## מבנה הריפו
```
BluetoothChat/
├── android/                 # אפליקציית Android (Gradle/Kotlin)
├── windows/BtChatWpf/       # תוכנת Windows (C#/WPF, 32feet.NET)
├── .github/workflows/       # קימפול אוטומטי בשני הענפים
└── PROTOCOL.md              # מפרט הפרוטוקול המשותף
```

## הקמת ריפו חדש ב-GitHub

1. צרו ריפו ריק חדש ב-GitHub (בלי README/gitignore, כדי לא להתנגש עם הקבצים כאן):
   `github.com/new`
2. בתיקייה המקומית שקיבלתם (`BluetoothChat/`) הריצו:
   ```bash
   cd BluetoothChat
   git init
   git add .
   git commit -m "Initial commit: Android + Windows Bluetooth chat"
   git branch -M main
   git remote add origin https://github.com/<your-username>/<repo-name>.git
   git push -u origin main
   ```
3. ברגע שדוחפים ל-`main`, שני ה-workflows ב-`.github/workflows/` ירוצו אוטומטית:
   - **Build Android APK** — יבנה `app-debug.apk`
   - **Build Windows App** — יבנה `BtChat.exe` (exe יחיד, לא דורש התקנת .NET במחשב היעד)
4. את הקבצים המוכנים מוצאים תחת הטאב **Actions** בריפו → בוחרים את ה-run האחרון →
   גוללים למטה ל-**Artifacts** ומורידים.

## הרצה מקומית (לפני שדוחפים ל-GitHub)

### Android
פותחים את תיקיית `android/` ב-Android Studio (או `./gradlew assembleDebug`
בטרמינל אם מותקן Android SDK מקומית) ומתקינים על מכשיר עם בלוטוס.

### Windows
פותחים את `windows/BtChatWpf/BtChatWpf.csproj` ב-Visual Studio 2022, או:
```powershell
cd windows/BtChatWpf
dotnet run
```

## איך משתמשים
1. קודם כל **מזווגים (Pair)** בין שני המכשירים דרך הגדרות הבלוטוס של המערכת
   (Windows Settings / Android Settings) — לפני פתיחת האפליקציה.
2. בצד אחד לוחצים **"האזן לחיבורים"** (הוא יהיה השרת וממתין).
3. בצד השני לוחצים **"התחבר למכשיר"** ובוחרים מהרשימה של המכשירים המזווגים.
4. לאחר החיבור אפשר לשלוח טקסט או ללחוץ 📎 לצירוף קובץ.

## הערות טכניות חשובות
- **פרוטוקול משותף**: שני הצדדים משתמשים באותו UUID של SPP ובאותו פורמט פריים
  (type + length + payload) — ראו `PROTOCOL.md`. אם רוצים להוסיף פיצ'רים (למשל
  אישורי קבלה, הצפנה, שמות משתמש) — יש לעדכן את שני הצדדים יחד.
- **Windows**: משתמשים בספריית [32feet.NET](https://github.com/inthehand/32feet)
  (NuGet: `InTheHand.Net.Bluetooth`) שעוטפת את ה-Win32 Bluetooth API הקלאסי —
  לא דורשת אריזת MSIX או הרשאות מיוחדות, בניגוד ל-API של WinRT.
- **Android**: דורש הרשאות `BLUETOOTH_CONNECT`/`BLUETOOTH_SCAN` (Android 12+)
  שנשאלות אוטומטית באפליקציה.
- קבצים שמתקבלים נשמרים: ב-Android תחת `Android/data/com.btchat.app/files/Downloads`,
  ב-Windows תחת `Documents\BtChat received`.

## הרחבות אפשריות (לא מומשו כרגע)
- העברת קבצים גדולים עם progress bar (כרגע כל קובץ נטען לזיכרון בבת אחת).
- תמיכה בכמה חיבורים בו-זמנית (כרגע 1-to-1 בלבד, לפי הבחירה שלכם).
- הצפנה מעל שכבת ה-SPP (Bluetooth pairing כבר מצפין את הערוץ עצמו ברמת ה-OS).
