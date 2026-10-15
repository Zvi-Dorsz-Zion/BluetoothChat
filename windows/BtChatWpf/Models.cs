using System;
using System.ComponentModel;
using System.IO;
using System.Runtime.CompilerServices;
using System.Threading.Tasks;
using System.Windows.Media.Imaging;

namespace BtChatWpf;

public enum MsgKind { Text, File }

public enum MsgStatus { Pending, Done, Failed }

public abstract class Notifier : INotifyPropertyChanged
{
    public event PropertyChangedEventHandler? PropertyChanged;
    protected void OnChanged([CallerMemberName] string? name = null) =>
        PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(name));
}

public sealed class ChatMessage : Notifier
{
    public bool Outgoing { get; init; }
    public MsgKind Kind { get; init; }
    public DateTime Time { get; init; } = DateTime.Now;
    public string? Text { get; init; }
    public string? FileName { get; init; }
    public string? FilePath { get; init; }
    public long FileSize { get; init; }

    private MsgStatus _status = MsgStatus.Done;
    public MsgStatus Status
    {
        get => _status;
        set
        {
            _status = value;
            OnChanged();
            OnChanged(nameof(MetaText));
            OnChanged(nameof(IsInProgress));
            OnChanged(nameof(FileInfoText));
        }
    }

    private double _progress;
    public double Progress
    {
        get => _progress;
        set
        {
            _progress = value;
            OnChanged();
            OnChanged(nameof(FileInfoText));
        }
    }

    private bool _viaWifi;
    /// The file went over the fast Wi-Fi path instead of Bluetooth.
    public bool ViaWifi
    {
        get => _viaWifi;
        set
        {
            _viaWifi = value;
            OnChanged();
            OnChanged(nameof(FileInfoText));
        }
    }

    public bool IsText => Kind == MsgKind.Text;
    public bool IsFile => Kind == MsgKind.File;
    public bool IsInProgress => Kind == MsgKind.File && Status == MsgStatus.Pending;

    public string MetaText
    {
        get
        {
            string time = Time.Date == DateTime.Today ? Time.ToString("HH:mm") : Time.ToString("dd/MM HH:mm");
            if (!Outgoing) return time;
            return Status switch
            {
                MsgStatus.Pending => time + "  ⏳",
                MsgStatus.Failed => time + "  ⚠ לא נשלח",
                _ => time + "  ✓"
            };
        }
    }

    public string FileInfoText
    {
        get
        {
            string size = FormatSize(FileSize) + (ViaWifi ? " · Wi-Fi ⚡" : "");
            return Status switch
            {
                MsgStatus.Pending => $"{size} · {(int)Progress}%",
                MsgStatus.Failed => $"{size} · נכשל",
                _ => size
            };
        }
    }

    public bool IsImage => IsFile && ImageTools.IsImageFile(FileName ?? "");

    private BitmapImage? _thumbnail;
    private bool _thumbnailRequested;

    /// Small preview for image files, loaded in the background on first use.
    public BitmapImage? Thumbnail
    {
        get
        {
            if (!_thumbnailRequested && IsImage && Status == MsgStatus.Done && FilePath != null && File.Exists(FilePath))
            {
                _thumbnailRequested = true;
                var path = FilePath;
                Task.Run(() =>
                {
                    try
                    {
                        var img = new BitmapImage();
                        img.BeginInit();
                        img.CacheOption = BitmapCacheOption.OnLoad;
                        img.DecodePixelWidth = 320;
                        img.UriSource = new Uri(path);
                        img.EndInit();
                        img.Freeze();
                        _thumbnail = img;
                        OnChanged(nameof(Thumbnail));
                        OnChanged(nameof(HasThumbnail));
                    }
                    catch { }
                });
            }
            return _thumbnail;
        }
    }

    public bool HasThumbnail => _thumbnail != null;

    public void RefreshThumbnail()
    {
        _thumbnailRequested = false;
        OnChanged(nameof(Thumbnail));
    }

    public static string FormatSize(long bytes) => bytes switch
    {
        < 1024 => $"{bytes} B",
        < 1024 * 1024 => $"{bytes / 1024.0:0} KB",
        < 1024L * 1024 * 1024 => $"{bytes / (1024.0 * 1024):0.0} MB",
        _ => $"{bytes / (1024.0 * 1024 * 1024):0.00} GB"
    };

    public HistoryRecord ToRecord() => new(
        Time, Outgoing, Kind.ToString(), Text, FileName, FilePath, FileSize, Status != MsgStatus.Failed, ViaWifi);

    public static ChatMessage FromRecord(HistoryRecord r) => new()
    {
        Time = r.Time,
        Outgoing = r.Out,
        Kind = r.Kind == nameof(MsgKind.File) ? MsgKind.File : MsgKind.Text,
        Text = r.Text,
        FileName = r.FileName,
        FilePath = r.FilePath,
        FileSize = r.Size,
        Status = r.Ok ? MsgStatus.Done : MsgStatus.Failed,
        ViaWifi = r.Wifi
    };
}

public sealed class DeviceItem : Notifier
{
    public required string Key { get; init; }
    public InTheHand.Net.BluetoothAddress? Address { get; set; }
    public bool IsPaired { get; set; }
    public DateTime LastActivity { get; set; }

    private string _displayName = "";
    public string DisplayName
    {
        get => _displayName;
        set { _displayName = value; OnChanged(); OnChanged(nameof(Initial)); }
    }

    private string _subtitle = "";
    public string Subtitle
    {
        get => _subtitle;
        set { _subtitle = value; OnChanged(); }
    }

    private bool _isConnected;
    public bool IsConnected
    {
        get => _isConnected;
        set { _isConnected = value; OnChanged(); }
    }

    public string Initial => DisplayName.Trim().Length > 0 ? DisplayName.Trim()[..1].ToUpperInvariant() : "?";

    public string AddressText => FormatKey(Key);

    public static string FormatKey(string key) =>
        key.Length == 12 && !key.StartsWith("name:")
            ? string.Join(":", Enumerable.Range(0, 6).Select(i => key.Substring(i * 2, 2)))
            : key.Replace("name:", "");
}
