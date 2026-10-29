using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Runtime.InteropServices;
using System.Text.Json;
using System.Windows.Media;
using System.Windows.Media.Imaging;

namespace BtChatWpf;

public static class AppPaths
{
    public static string DataDir { get; } = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "BtChat");

    [DllImport("shell32.dll", CharSet = CharSet.Unicode)]
    private static extern int SHGetKnownFolderPath(
        [MarshalAs(UnmanagedType.LPStruct)] Guid rfid, uint flags, IntPtr token, out IntPtr path);

    /// The real Downloads folder (works even if the user moved it).
    public static string Downloads
    {
        get
        {
            try
            {
                if (SHGetKnownFolderPath(new Guid("374DE290-123F-4565-9164-39C4925E467B"), 0, IntPtr.Zero, out var p) == 0)
                {
                    var s = Marshal.PtrToStringUni(p);
                    Marshal.FreeCoTaskMem(p);
                    if (!string.IsNullOrEmpty(s)) return s;
                }
            }
            catch { }
            return Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "Downloads");
        }
    }

    public static string DefaultSaveFolder => Path.Combine(Downloads, "BT Chat");
}

public sealed class AppSettings
{
    public bool Notifications { get; set; } = true;
    public bool CloseToTray { get; set; } = true;
    public bool WifiEnabled { get; set; } = true;
    /// Direct network (phone hotspot) when there is no common Wi-Fi: "off", "auto" or "always".
    public string DirectMode { get; set; } = "auto";
    /// Devices that connected through BT Chat at least once (only these appear in the list).
    public List<string> KnownDevices { get; set; } = new();
    public string? SaveFolder { get; set; }
    /// "ask", "reduced" or "original"
    public string ImageQuality { get; set; } = "ask";
    public List<string> Trusted { get; set; } = new();
    public Dictionary<string, string> Nicknames { get; set; } = new();
    public Dictionary<string, string> KnownNames { get; set; } = new();
    public string? LastDevice { get; set; }
    public bool TrayHintShown { get; set; }

    private static string FilePath => Path.Combine(AppPaths.DataDir, "settings.json");

    public string EffectiveSaveFolder =>
        string.IsNullOrWhiteSpace(SaveFolder) ? AppPaths.DefaultSaveFolder : SaveFolder!;

    public static AppSettings Load()
    {
        try
        {
            if (File.Exists(FilePath))
                return JsonSerializer.Deserialize<AppSettings>(File.ReadAllText(FilePath)) ?? new AppSettings();
        }
        catch { }
        return new AppSettings();
    }

    public void Save()
    {
        try
        {
            Directory.CreateDirectory(AppPaths.DataDir);
            File.WriteAllText(FilePath, JsonSerializer.Serialize(this, new JsonSerializerOptions { WriteIndented = true }));
        }
        catch { }
    }
}

public sealed record HistoryRecord(
    DateTime Time, bool Out, string Kind, string? Text, string? FileName, string? FilePath, long Size, bool Ok,
    bool Wifi = false);

/// Chat history per device: one JSON-lines file per device key.
public sealed class HistoryStore
{
    private readonly object _lock = new();
    private static string Dir => Path.Combine(AppPaths.DataDir, "history");

    private static string FileFor(string key)
    {
        var safe = string.Concat(key.Select(c => Path.GetInvalidFileNameChars().Contains(c) ? '_' : c));
        return Path.Combine(Dir, safe + ".jsonl");
    }

    public List<ChatMessage> Load(string key)
    {
        lock (_lock)
        {
            var list = new List<ChatMessage>();
            var file = FileFor(key);
            if (!File.Exists(file)) return list;
            foreach (var line in File.ReadAllLines(file))
            {
                if (string.IsNullOrWhiteSpace(line)) continue;
                try
                {
                    var r = JsonSerializer.Deserialize<HistoryRecord>(line);
                    if (r != null) list.Add(ChatMessage.FromRecord(r));
                }
                catch { }
            }
            return list;
        }
    }

    public void Append(string key, ChatMessage message)
    {
        lock (_lock)
        {
            try
            {
                Directory.CreateDirectory(Dir);
                File.AppendAllText(FileFor(key), JsonSerializer.Serialize(message.ToRecord()) + "\n");
            }
            catch { }
        }
    }

    public void Clear(string key)
    {
        lock (_lock)
        {
            try { File.Delete(FileFor(key)); } catch { }
        }
    }

    public HistoryRecord? Last(string key)
    {
        lock (_lock)
        {
            try
            {
                var file = FileFor(key);
                if (!File.Exists(file)) return null;
                var line = File.ReadLines(file).LastOrDefault(l => !string.IsNullOrWhiteSpace(l));
                return line == null ? null : JsonSerializer.Deserialize<HistoryRecord>(line);
            }
            catch { return null; }
        }
    }

    public IEnumerable<string> Keys()
    {
        lock (_lock)
        {
            if (!Directory.Exists(Dir)) return Array.Empty<string>();
            return Directory.GetFiles(Dir, "*.jsonl")
                .Where(f => new FileInfo(f).Length > 0)
                .Select(f => Path.GetFileNameWithoutExtension(f))
                .Where(k => k.Length == 12)
                .ToList();
        }
    }
}

public static class FileUtil
{
    public static string Sanitize(string name)
    {
        name = Path.GetFileName(name.Replace('\\', '/').Split('/').Last());
        var invalid = Path.GetInvalidFileNameChars();
        var cleaned = new string(name.Select(c => invalid.Contains(c) || char.IsControl(c) ? '_' : c).ToArray())
            .Trim().TrimStart('.');
        if (cleaned.Length == 0) cleaned = $"file_{DateTime.Now:yyyyMMdd_HHmmss}";
        return cleaned.Length > 150 ? cleaned.Substring(0, 150) : cleaned;
    }

    /// "a.jpg" -> "a (1).jpg" if it already exists.
    public static string UniquePath(string folder, string name)
    {
        var path = Path.Combine(folder, name);
        if (!File.Exists(path)) return path;
        var stem = Path.GetFileNameWithoutExtension(name);
        var ext = Path.GetExtension(name);
        for (int i = 1; ; i++)
        {
            path = Path.Combine(folder, $"{stem} ({i}){ext}");
            if (!File.Exists(path)) return path;
        }
    }
}

/// "Reduced quality" for photos: max 1600px on the long side, JPEG 75%.
public static class ImageTools
{
    private const int MaxSide = 1600;
    private static readonly string[] Extensions = { ".jpg", ".jpeg", ".png", ".bmp", ".tif", ".tiff", ".heic", ".webp", ".gif" };
    private static readonly string[] Reducible = { ".jpg", ".jpeg", ".png", ".bmp", ".tif", ".tiff" };

    public static bool IsImageFile(string name) =>
        Extensions.Contains(Path.GetExtension(name).ToLowerInvariant());

    public static bool CanReduce(string path) =>
        Reducible.Contains(Path.GetExtension(path).ToLowerInvariant());

    /// Returns the path of a smaller JPEG in %TEMP%, or null if it would not be smaller.
    public static string? Reduce(string path)
    {
        try
        {
            var decoder = BitmapDecoder.Create(new Uri(path), BitmapCreateOptions.None, BitmapCacheOption.OnLoad);
            BitmapSource frame = decoder.Frames[0];

            int orientation = 1;
            try
            {
                if (frame.Metadata is BitmapMetadata meta && meta.ContainsQuery("System.Photo.Orientation"))
                    orientation = Convert.ToInt32(meta.GetQuery("System.Photo.Orientation"));
            }
            catch { }

            BitmapSource result = frame;
            int longest = Math.Max(frame.PixelWidth, frame.PixelHeight);
            if (longest > MaxSide)
            {
                double scale = (double)MaxSide / longest;
                result = new TransformedBitmap(result, new ScaleTransform(scale, scale));
            }
            int angle = orientation switch { 3 => 180, 6 => 90, 8 => 270, _ => 0 };
            if (angle != 0) result = new TransformedBitmap(result, new RotateTransform(angle));

            var encoder = new JpegBitmapEncoder { QualityLevel = 75 };
            encoder.Frames.Add(BitmapFrame.Create(result));
            var dir = Path.Combine(Path.GetTempPath(), "BtChat", DateTime.Now.Ticks.ToString());
            Directory.CreateDirectory(dir);
            var outPath = Path.Combine(dir, Path.GetFileNameWithoutExtension(path) + ".jpg");
            using (var fs = File.Create(outPath)) encoder.Save(fs);

            return new FileInfo(outPath).Length < new FileInfo(path).Length ? outPath : null;
        }
        catch
        {
            return null;
        }
    }
}
