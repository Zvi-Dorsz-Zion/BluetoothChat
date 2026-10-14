using System;
using System.Collections.Generic;
using System.Collections.ObjectModel;
using System.ComponentModel;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Threading;
using InTheHand.Net;
using InTheHand.Net.Sockets;
using Microsoft.Win32;

namespace BtChatWpf;

public partial class MainWindow : Window
{
    private enum State { Idle, Listening, Connecting, AwaitingApproval, IncomingRequest, Connected }

    private readonly BluetoothService _bt = new();
    private readonly AppSettings _settings = AppSettings.Load();
    private readonly HistoryStore _history = new();
    private readonly ObservableCollection<DeviceItem> _devices = new();
    private readonly ObservableCollection<ChatMessage> _messages = new();
    private readonly System.Windows.Forms.NotifyIcon _tray = new();
    private readonly DispatcherTimer _retryListen = new() { Interval = TimeSpan.FromSeconds(4) };

    private State _state = State.Idle;
    private PeerInfo? _peer;
    private string? _conversationKey;
    private string? _pendingConnectKey;
    private bool _cancelling;
    private bool _exiting;
    private bool _sidebarUserToggled;
    private DialogWindow? _requestDialog;
    private DispatcherTimer? _requestTimer;

    public MainWindow()
    {
        InitializeComponent();
        ListDevices.ItemsSource = _devices;
        ListMessages.ItemsSource = _messages;
        SetupTray();
        WireBluetooth();
        _retryListen.Tick += (_, _) => { _retryListen.Stop(); StartListening(); };

        Loaded += (_, _) =>
        {
            RefreshDevices();
            if (_settings.LastDevice != null) OpenConversation(_settings.LastDevice);
            StartListening();
            UpdateUi();
            EditMessage.Focus();
        };
    }

    // ================================================================ tray & window

    private void SetupTray()
    {
        try
        {
            var stream = Application.GetResourceStream(new Uri("pack://application:,,,/app.ico"))?.Stream;
            _tray.Icon = stream != null ? new System.Drawing.Icon(stream) : System.Drawing.SystemIcons.Application;
        }
        catch
        {
            _tray.Icon = System.Drawing.SystemIcons.Application;
        }
        _tray.Text = "BT Chat";
        _tray.Visible = true;
        _tray.DoubleClick += (_, _) => Dispatcher.Invoke(BringToFront);
        _tray.BalloonTipClicked += (_, _) => Dispatcher.Invoke(BringToFront);
        var menu = new System.Windows.Forms.ContextMenuStrip { RightToLeft = System.Windows.Forms.RightToLeft.Yes };
        menu.Items.Add("פתח את BT Chat", null, (_, _) => Dispatcher.Invoke(BringToFront));
        menu.Items.Add("יציאה", null, (_, _) => Dispatcher.Invoke(ExitApp));
        _tray.ContextMenuStrip = menu;
    }

    public void BringToFront()
    {
        Show();
        if (WindowState == WindowState.Minimized) WindowState = WindowState.Normal;
        Activate();
        Topmost = true;  // reliably jump in front of other windows
        Topmost = false;
        Focus();
    }

    private bool IsUserLooking => IsVisible && IsActive && WindowState != WindowState.Minimized;

    private void Notify(string title, string text, bool force = false)
    {
        if (!force && (!_settings.Notifications || IsUserLooking)) return;
        if (text.Length > 200) text = text.Substring(0, 200) + "...";
        _tray.ShowBalloonTip(5000, title, text, System.Windows.Forms.ToolTipIcon.Info);
    }

    private void Window_Closing(object? sender, CancelEventArgs e)
    {
        if (_exiting) return;
        if (_settings.CloseToTray)
        {
            e.Cancel = true;
            Hide();
            if (!_settings.TrayHintShown)
            {
                _settings.TrayHintShown = true;
                _settings.Save();
                Notify("BT Chat ממשיך לפעול ברקע",
                    "כדי לקבל הודעות וחיבורים. ליציאה מלאה: לחצן ימני על האייקון ליד השעון ← יציאה.", force: true);
            }
            return;
        }
        e.Cancel = true;
        ExitApp();
    }

    private void ExitApp()
    {
        _exiting = true;
        _bt.Shutdown();
        _tray.Visible = false;
        _tray.Dispose();
        Close();
        Application.Current.Shutdown();
    }

    private void Window_SizeChanged(object sender, SizeChangedEventArgs e)
    {
        if (!_sidebarUserToggled) SetSidebar(ActualWidth >= 700);
    }

    private void SetSidebar(bool visible)
    {
        Sidebar.Visibility = visible ? Visibility.Visible : Visibility.Collapsed;
        SidebarColumn.Width = visible ? new GridLength(Math.Min(280, Math.Max(220, ActualWidth * 0.4))) : new GridLength(0);
    }

    private void BtnToggleSidebar_Click(object sender, RoutedEventArgs e)
    {
        _sidebarUserToggled = true;
        SetSidebar(Sidebar.Visibility != Visibility.Visible);
    }

    // ================================================================ bluetooth events

    private void WireBluetooth()
    {
        _bt.ListenFailed += msg => Ui(() =>
        {
            if (_state == State.Listening) SetState(State.Idle);
            TxtListenState.Text = "Bluetooth לא זמין - מנסה שוב...";
            _retryListen.Stop();
            _retryListen.Start();
        });

        _bt.IncomingRequest += peer => Ui(() =>
        {
            _peer = peer;
            _cancelling = false;
            RememberName(peer);
            SetState(State.IncomingRequest);
            if (_settings.Trusted.Contains(peer.Key)) AcceptIncoming(false);
            else ShowRequestDialog(peer);
        });

        _bt.AwaitingApproval += _ => Ui(() =>
        {
            if (_state == State.Connecting) SetState(State.AwaitingApproval);
        });

        _bt.Connected += peer => Ui(() =>
        {
            if (_state == State.AwaitingApproval || _state == State.Connecting) MarkConnected(peer);
        });

        _bt.ConnectFailed += (peer, _) => Ui(() =>
        {
            if (_state != State.Connecting) return;
            if (!_cancelling)
            {
                ShowInfo("לא ניתן להתחבר",
                    $"לא ניתן להתחבר אל {DisplayName(peer.Key)}.\nודא ש-BT Chat פתוח במכשיר השני ושה-Bluetooth פעיל בו.");
            }
            AfterConnectionEnded();
        });

        _bt.Rejected += peer => Ui(() =>
        {
            _cancelling = true;
            ShowInfo("החיבור נדחה", $"{DisplayName(peer.Key)} דחה את בקשת החיבור.");
        });

        _bt.Disconnected += peer => Ui(() =>
        {
            var was = _state;
            if (was == State.IncomingRequest) CloseRequestDialog();
            if (!_cancelling && was == State.Connected)
            {
                TxtStatus.Text = "החיבור נותק";
                Notify("BT Chat", $"החיבור עם {DisplayName(peer.Key)} נותק");
            }
            if (was != State.Listening && was != State.Idle) AfterConnectionEnded();
        });

        _bt.TextReceived += text => Ui(() =>
        {
            var key = _peer?.Key;
            if (key == null) return;
            var msg = new ChatMessage { Outgoing = false, Kind = MsgKind.Text, Text = text };
            _history.Append(key, msg);
            Show(key, msg);
            Notify(DisplayName(key), text);
            RefreshDeviceSubtitle(key);
        });

        _bt.FileIncoming = (name, size) =>
        {
            string? key = null;
            Dispatcher.Invoke(() => key = _peer?.Key);
            if (key == null) return null;

            string path;
            FileStream stream;
            try
            {
                var folder = _settings.EffectiveSaveFolder;
                Directory.CreateDirectory(folder);
                path = FileUtil.UniquePath(folder, FileUtil.Sanitize(name));
                stream = new FileStream(path, FileMode.CreateNew, FileAccess.Write);
            }
            catch (Exception ex)
            {
                Ui(() => ShowInfo("שגיאה בשמירת קובץ", $"לא ניתן לשמור את {name}:\n{ex.Message}"));
                return null;
            }

            var msg = new ChatMessage
            {
                Outgoing = false, Kind = MsgKind.File, FileName = Path.GetFileName(path),
                FilePath = path, FileSize = size, Status = MsgStatus.Pending
            };
            Ui(() => Show(key, msg));
            return new IncomingFile(stream, size,
                percent => Ui(() => msg.Progress = percent),
                ok => Ui(() =>
                {
                    msg.Status = ok ? MsgStatus.Done : MsgStatus.Failed;
                    if (!ok) { try { File.Delete(path); } catch { } }
                    else msg.RefreshThumbnail();
                    _history.Append(key, msg);
                    if (ok) Notify(DisplayName(key), "📎 " + msg.FileName);
                    RefreshDeviceSubtitle(key);
                }));
        };
    }

    private sealed class IncomingFile : IIncomingFile
    {
        private readonly long _size;
        private readonly Action<int> _progress;
        private readonly Action<bool> _finished;
        private int _lastPercent = -1;

        public IncomingFile(Stream output, long size, Action<int> progress, Action<bool> finished)
        {
            Output = output;
            _size = size;
            _progress = progress;
            _finished = finished;
        }

        public Stream Output { get; }

        public void Progress(long received)
        {
            int p = _size <= 0 ? 100 : (int)(received * 100 / _size);
            if (p == _lastPercent) return;
            _lastPercent = p;
            _progress(p);
        }

        public void Finished(bool success) => _finished(success);
    }

    private void Ui(Action action) => Dispatcher.BeginInvoke(action);

    // ================================================================ state

    private void StartListening()
    {
        if (_state is State.Connecting or State.AwaitingApproval or State.IncomingRequest or State.Connected) return;
        if (_bt.HasConnection) return;
        _bt.StartListening();
        SetState(State.Listening);
    }

    private void SetState(State state)
    {
        _state = state;
        UpdateUi();
    }

    private void MarkConnected(PeerInfo peer)
    {
        _peer = peer;
        _cancelling = false;
        RememberName(peer);
        if (_devices.All(d => d.Key != peer.Key)) RefreshDevices();
        if (_conversationKey != peer.Key) OpenConversation(peer.Key);
        SetState(State.Connected);
    }

    private void AfterConnectionEnded()
    {
        _peer = null;
        _cancelling = false;
        SetState(State.Idle);
        var next = _pendingConnectKey;
        _pendingConnectKey = null;
        if (next != null)
        {
            Connect(next);
            return;
        }
        var t = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(400) };
        t.Tick += (_, _) => { t.Stop(); StartListening(); };
        t.Start();
    }

    private void Connect(string key)
    {
        if (_state is State.Connected or State.Connecting or State.AwaitingApproval or State.IncomingRequest)
        {
            if (_peer?.Key == key && _state != State.IncomingRequest) return;
            _pendingConnectKey = key;
            Disconnect();
            return;
        }
        var device = _devices.FirstOrDefault(d => d.Key == key);
        if (device?.Address == null)
        {
            ShowInfo("המכשיר לא מזווג",
                "צריך לצמד את המכשיר קודם.\nלחץ \"צמד מכשיר חדש\" או צמד דרך הגדרות ה-Bluetooth של Windows.");
            return;
        }
        _cancelling = false;
        _peer = new PeerInfo(key, DisplayName(key), device.Address);
        OpenConversation(key);
        SetState(State.Connecting);
        _bt.Connect(_peer);
    }

    private void Disconnect()
    {
        if (_state == State.IncomingRequest)
        {
            RejectIncoming();
            return;
        }
        _cancelling = true;
        _bt.Disconnect();
    }

    // ================================================================ incoming request

    private void ShowRequestDialog(PeerInfo peer)
    {
        Notify("בקשת חיבור", $"{DisplayName(peer.Key)} מבקש להתחבר", force: _settings.Notifications);
        BringToFront();

        _requestTimer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(60) };
        _requestTimer.Tick += (_, _) => CloseRequestDialog();
        _requestTimer.Start();

        _requestDialog = new DialogWindow(
            "בקשת חיבור",
            $"{DisplayName(peer.Key)} ({DeviceItem.FormatKey(peer.Key)}) מבקש להתחבר אליך.",
            new[] { "אשר", "דחה" },
            checkbox: "אשר תמיד את המכשיר הזה (בלי לשאול שוב)");
        int result = _requestDialog.ShowFor(this);
        bool remember = _requestDialog.IsChecked;
        _requestDialog = null;
        _requestTimer?.Stop();
        _requestTimer = null;

        if (_state != State.IncomingRequest || _peer?.Key != peer.Key) return;
        if (result == 0) AcceptIncoming(remember);
        else RejectIncoming();
    }

    private void CloseRequestDialog()
    {
        _requestTimer?.Stop();
        _requestTimer = null;
        _requestDialog?.Dismiss();
    }

    private void AcceptIncoming(bool remember)
    {
        if (_state != State.IncomingRequest || _peer == null) return;
        if (remember && !_settings.Trusted.Contains(_peer.Key))
        {
            _settings.Trusted.Add(_peer.Key);
            _settings.Save();
        }
        _bt.Approve();
        MarkConnected(_peer);
    }

    private void RejectIncoming()
    {
        if (_state != State.IncomingRequest) return;
        _cancelling = true;
        _bt.Reject();
    }

    // ================================================================ devices & conversations

    private string DisplayName(string key)
    {
        if (_settings.Nicknames.TryGetValue(key, out var nick)) return nick;
        if (_settings.KnownNames.TryGetValue(key, out var known)) return known;
        var device = _devices.FirstOrDefault(d => d.Key == key);
        return device?.DisplayName is { Length: > 0 } n ? n : DeviceItem.FormatKey(key);
    }

    private void RememberName(PeerInfo peer)
    {
        if (string.IsNullOrWhiteSpace(peer.Name)) return;
        if (_settings.KnownNames.TryGetValue(peer.Key, out var old) && old == peer.Name) return;
        _settings.KnownNames[peer.Key] = peer.Name;
        _settings.Save();
    }

    private async void RefreshDevices()
    {
        List<BluetoothDeviceInfo> paired;
        try
        {
            paired = await Task.Run(() =>
            {
                using var client = new BluetoothClient();
                return client.PairedDevices.ToList();
            });
        }
        catch
        {
            paired = new List<BluetoothDeviceInfo>();
        }

        var items = new Dictionary<string, DeviceItem>();
        foreach (var d in paired)
        {
            var key = BluetoothService.KeyOf(d.DeviceAddress);
            if (!string.IsNullOrWhiteSpace(d.DeviceName)) _settings.KnownNames[key] = d.DeviceName;
            items[key] = new DeviceItem { Key = key, Address = d.DeviceAddress, IsPaired = true };
        }
        foreach (var key in _history.Keys())
        {
            if (!items.ContainsKey(key)) items[key] = new DeviceItem { Key = key, IsPaired = false };
        }
        _settings.Save();

        foreach (var item in items.Values)
        {
            var last = _history.Last(item.Key);
            item.LastActivity = last?.Time ?? DateTime.MinValue;
            item.DisplayName = DisplayName(item.Key);
            item.Subtitle = SubtitleFor(item, last);
        }

        var selectedKey = _conversationKey;
        _devices.Clear();
        foreach (var item in items.Values.OrderByDescending(i => i.LastActivity).ThenBy(i => i.DisplayName))
            _devices.Add(item);
        TxtNoDevices.Visibility = _devices.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
        SelectDeviceInList(selectedKey);
        UpdateUi();
    }

    private static string SubtitleFor(DeviceItem item, HistoryRecord? last)
    {
        if (last == null) return item.IsPaired ? "מזווג · " + item.AddressText : "לא מזווג";
        var text = last.Kind == nameof(MsgKind.File) ? "📎 " + last.FileName : last.Text ?? "";
        return text.Replace('\n', ' ');
    }

    private void RefreshDeviceSubtitle(string key)
    {
        var item = _devices.FirstOrDefault(d => d.Key == key);
        if (item != null) item.Subtitle = SubtitleFor(item, _history.Last(key));
    }

    private bool _selectingProgrammatically;

    private void SelectDeviceInList(string? key)
    {
        _selectingProgrammatically = true;
        ListDevices.SelectedItem = key == null ? null : _devices.FirstOrDefault(d => d.Key == key);
        _selectingProgrammatically = false;
    }

    private void OpenConversation(string key)
    {
        _conversationKey = key;
        if (_settings.LastDevice != key)
        {
            _settings.LastDevice = key;
            _settings.Save();
        }
        _messages.Clear();
        foreach (var m in _history.Load(key)) _messages.Add(m);
        SelectDeviceInList(key);
        UpdateUi();
        ScrollToEnd();
    }

    private void Show(string key, ChatMessage msg)
    {
        if (_conversationKey != key) return;
        _messages.Add(msg);
        UpdateEmptyState();
        ScrollToEnd();
    }

    private void ScrollToEnd()
    {
        if (_messages.Count == 0) return;
        Dispatcher.BeginInvoke(DispatcherPriority.Background,
            new Action(() => ListMessages.ScrollIntoView(_messages[^1])));
    }

    private void ListDevices_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (_selectingProgrammatically) return;
        if (ListDevices.SelectedItem is DeviceItem item && item.Key != _conversationKey)
        {
            OpenConversation(item.Key);
            if (ActualWidth < 700) SetSidebar(false);
        }
    }

    private void ListDevices_MouseDoubleClick(object sender, MouseButtonEventArgs e)
    {
        if (ListDevices.SelectedItem is DeviceItem item) Connect(item.Key);
    }

    private static DeviceItem? MenuDevice(object sender) => (sender as FrameworkElement)?.DataContext as DeviceItem;

    private void DeviceMenuConnect_Click(object sender, RoutedEventArgs e)
    {
        if (MenuDevice(sender) is { } d) Connect(d.Key);
    }

    private void DeviceMenuRename_Click(object sender, RoutedEventArgs e)
    {
        if (MenuDevice(sender) is { } d) RenameDevice(d.Key);
    }

    private void DeviceMenuClear_Click(object sender, RoutedEventArgs e)
    {
        if (MenuDevice(sender) is not { } d) return;
        int answer = DialogWindow.Ask(this, "מחיקת היסטוריה",
            $"למחוק את כל ההודעות עם {DisplayName(d.Key)}?\nקבצים שכבר נשמרו לא יימחקו.", "מחק", "ביטול");
        if (answer != 0) return;
        _history.Clear(d.Key);
        if (_conversationKey == d.Key) _messages.Clear();
        RefreshDeviceSubtitle(d.Key);
        UpdateEmptyState();
    }

    private void RenameDevice(string key)
    {
        var current = _settings.Nicknames.TryGetValue(key, out var nick) ? nick : DisplayName(key);
        var dialog = new DialogWindow("שם למכשיר", "השם יוצג רק אצלך. השאר ריק כדי לחזור לשם המקורי.",
            new[] { "שמור", "ביטול" }, input: current);
        if (dialog.ShowFor(this) != 0) return;
        var name = dialog.InputText.Trim();
        if (name.Length == 0) _settings.Nicknames.Remove(key);
        else _settings.Nicknames[key] = name;
        _settings.Save();
        var item = _devices.FirstOrDefault(d => d.Key == key);
        if (item != null) item.DisplayName = DisplayName(key);
        UpdateUi();
    }

    private void BtnRename_Click(object sender, RoutedEventArgs e)
    {
        if (_conversationKey != null) RenameDevice(_conversationKey);
    }

    private void BtnConnect_Click(object sender, RoutedEventArgs e)
    {
        switch (_state)
        {
            case State.Connected:
            case State.Connecting:
            case State.AwaitingApproval:
                Disconnect();
                break;
            case State.IncomingRequest:
                break;
            default:
                if (_conversationKey != null) Connect(_conversationKey);
                break;
        }
    }

    private void BtnPairNew_Click(object sender, RoutedEventArgs e)
    {
        var picker = new DevicePickerWindow { Owner = this };
        if (picker.ShowDialog() != true || picker.PairedDevice == null) return;
        var device = picker.PairedDevice;
        var key = BluetoothService.KeyOf(device.DeviceAddress);
        if (!string.IsNullOrWhiteSpace(device.DeviceName))
        {
            _settings.KnownNames[key] = device.DeviceName;
            _settings.Save();
        }
        var item = _devices.FirstOrDefault(d => d.Key == key);
        if (item == null)
        {
            item = new DeviceItem { Key = key, Address = device.DeviceAddress, IsPaired = true };
            _devices.Insert(0, item);
        }
        item.Address = device.DeviceAddress;
        item.IsPaired = true;
        item.DisplayName = DisplayName(key);
        item.Subtitle = SubtitleFor(item, _history.Last(key));
        TxtNoDevices.Visibility = Visibility.Collapsed;
        Connect(key);
    }

    private void BtnSettings_Click(object sender, RoutedEventArgs e)
    {
        var window = new SettingsWindow(_settings, DisplayName) { Owner = this };
        if (window.ShowDialog() == true) _settings.Save();
    }

    // ================================================================ sending

    private bool CanSend => _state == State.Connected && _peer != null && _conversationKey == _peer.Key;

    private void EditMessage_PreviewKeyDown(object sender, KeyEventArgs e)
    {
        if (e.Key == Key.Enter && (Keyboard.Modifiers & ModifierKeys.Shift) == 0)
        {
            e.Handled = true;
            SendCurrentText();
        }
    }

    private void EditMessage_TextChanged(object sender, TextChangedEventArgs e)
    {
        TxtPlaceholder.Visibility = EditMessage.Text.Length == 0 ? Visibility.Visible : Visibility.Collapsed;
        BtnSend.IsEnabled = CanSend && EditMessage.Text.Trim().Length > 0;
    }

    private void BtnSend_Click(object sender, RoutedEventArgs e) => SendCurrentText();

    private async void SendCurrentText()
    {
        var text = EditMessage.Text.Trim();
        if (text.Length == 0 || !CanSend || _peer == null) return;
        var key = _peer.Key;
        EditMessage.Clear();
        var msg = new ChatMessage { Outgoing = true, Kind = MsgKind.Text, Text = text, Status = MsgStatus.Pending };
        Show(key, msg);
        bool ok = await _bt.SendTextAsync(text);
        msg.Status = ok ? MsgStatus.Done : MsgStatus.Failed;
        _history.Append(key, msg);
        RefreshDeviceSubtitle(key);
    }

    private void BtnAttach_Click(object sender, RoutedEventArgs e)
    {
        if (!CanSend) return;
        var dialog = new OpenFileDialog { Title = "בחר קבצים לשליחה", Multiselect = true };
        if (dialog.ShowDialog(this) == true) SendFiles(dialog.FileNames);
    }

    private async void SendFiles(IReadOnlyList<string> paths)
    {
        if (!CanSend || _peer == null)
        {
            ShowInfo("אין חיבור", "התחבר למכשיר כדי לשלוח קבצים.");
            return;
        }
        var key = _peer.Key;

        bool reduce = false;
        int images = paths.Count(p => ImageTools.CanReduce(p));
        if (images > 0)
        {
            switch (_settings.ImageQuality)
            {
                case "reduced": reduce = true; break;
                case "original": reduce = false; break;
                default:
                    int answer = DialogWindow.Ask(this,
                        images == 1 ? "שליחת תמונה" : $"שליחת {images} תמונות",
                        "באיזו איכות לשלוח?\nאיכות מוקטנת נשלחת מהר בהרבה ומתאימה לצפייה רגילה.",
                        "מוקטנת (מהיר)", "מקורית", "ביטול");
                    if (answer < 0 || answer == 2) return;
                    reduce = answer == 0;
                    break;
            }
        }

        foreach (var path in paths)
        {
            if (!File.Exists(path)) continue;
            string sendPath = path;
            if (reduce && ImageTools.CanReduce(path))
                sendPath = await Task.Run(() => ImageTools.Reduce(path)) ?? path;

            long size = new FileInfo(sendPath).Length;
            if (size > BtProtocol.MaxFileSize)
            {
                ShowInfo("קובץ גדול מדי", $"{Path.GetFileName(path)} גדול מ-2GB ולא ניתן לשלוח אותו.");
                continue;
            }
            var name = Path.GetFileName(sendPath);
            var msg = new ChatMessage
            {
                Outgoing = true, Kind = MsgKind.File, FileName = name, FilePath = path,
                FileSize = size, Status = MsgStatus.Pending
            };
            Show(key, msg);
            int last = -1;
            bool ok = await _bt.SendFileAsync(sendPath, name, sent =>
            {
                int p = size == 0 ? 100 : (int)(sent * 100 / size);
                if (p == last) return;
                last = p;
                Ui(() => msg.Progress = p);
            });
            msg.Status = ok ? MsgStatus.Done : MsgStatus.Failed;
            msg.RefreshThumbnail();
            _history.Append(key, msg);
            RefreshDeviceSubtitle(key);
            if (!ok) break; // connection is gone - stop the queue
        }
    }

    private void Window_DragEnter(object sender, DragEventArgs e)
    {
        bool ok = CanSend && e.Data.GetDataPresent(DataFormats.FileDrop);
        e.Effects = ok ? DragDropEffects.Copy : DragDropEffects.None;
        DropOverlay.Visibility = ok ? Visibility.Visible : Visibility.Collapsed;
    }

    private void Window_DragLeave(object sender, DragEventArgs e) => DropOverlay.Visibility = Visibility.Collapsed;

    private void Window_Drop(object sender, DragEventArgs e)
    {
        DropOverlay.Visibility = Visibility.Collapsed;
        if (e.Data.GetData(DataFormats.FileDrop) is string[] files)
            SendFiles(files.Where(File.Exists).ToList());
    }

    // ================================================================ message actions

    private static ChatMessage? MessageOf(object sender) => (sender as FrameworkElement)?.DataContext as ChatMessage;

    private void File_Click(object sender, MouseButtonEventArgs e)
    {
        if (MessageOf(sender) is { } m) OpenFile(m);
    }

    private void MenuOpen_Click(object sender, RoutedEventArgs e)
    {
        if (MessageOf(sender) is { } m) OpenFile(m);
    }

    private void MenuShowInFolder_Click(object sender, RoutedEventArgs e)
    {
        if (MessageOf(sender) is not { } m || !FileAvailable(m)) return;
        Process.Start("explorer.exe", $"/select,\"{m.FilePath}\"");
    }

    private void MenuCopyPath_Click(object sender, RoutedEventArgs e)
    {
        if (MessageOf(sender)?.FilePath is { } path) Clipboard.SetText(path);
    }

    private void MenuCopyText_Click(object sender, RoutedEventArgs e)
    {
        if (MessageOf(sender)?.Text is { } text) Clipboard.SetText(text);
    }

    private bool FileAvailable(ChatMessage m)
    {
        if (m.Status == MsgStatus.Pending)
        {
            ShowInfo("הקובץ עוד בהעברה", "אפשר לפתוח אותו כשההעברה תסתיים.");
            return false;
        }
        if (m.FilePath == null || !File.Exists(m.FilePath))
        {
            ShowInfo("הקובץ לא נמצא", "ייתכן שהקובץ נמחק או הועבר למקום אחר.");
            return false;
        }
        return true;
    }

    private void OpenFile(ChatMessage m)
    {
        if (!FileAvailable(m)) return;
        try { Process.Start(new ProcessStartInfo(m.FilePath!) { UseShellExecute = true }); }
        catch (Exception ex) { ShowInfo("לא ניתן לפתוח", ex.Message); }
    }

    // ================================================================ UI

    private void ShowInfo(string title, string message)
    {
        if (!IsUserLooking) Notify(title, message, force: true);
        else DialogWindow.Ask(this, title, message, "אישור");
    }

    private void UpdateEmptyState()
    {
        EmptyState.Visibility = _messages.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
        TxtEmpty.Text = _conversationKey == null
            ? "בחר מכשיר מהרשימה כדי לראות את השיחה ולהתחבר,\nאו המתן שמכשיר אחר יתחבר אליך."
            : "אין הודעות עדיין";
    }

    private void UpdateUi()
    {
        string peerName = _peer != null ? DisplayName(_peer.Key) : "";
        TxtListenState.Text = _state switch
        {
            State.Listening => "מוכן לקבל חיבורים",
            State.Connected => "מחובר אל " + peerName,
            State.Connecting => "מתחבר...",
            State.AwaitingApproval => "ממתין לאישור...",
            State.IncomingRequest => peerName + " מבקש להתחבר",
            _ => "לא מאזין כרגע"
        };

        TxtPeerName.Text = _conversationKey != null ? DisplayName(_conversationKey) : "BT Chat";
        bool showingPeer = _peer != null && _conversationKey == _peer.Key;
        (string status, Brush dot) = _state switch
        {
            State.Connected when showingPeer => ("מחובר", (Brush)FindResource("Ok")),
            State.Connected => ($"מחובר כרגע אל {peerName}", (Brush)FindResource("Off")),
            State.Connecting => ($"מתחבר אל {peerName}...", (Brush)FindResource("Brand")),
            State.AwaitingApproval => ($"ממתין לאישור ב-{peerName}...", (Brush)FindResource("Brand")),
            State.IncomingRequest => ($"{peerName} מבקש להתחבר", (Brush)FindResource("Brand")),
            _ => ("לא מחובר", (Brush)FindResource("Off"))
        };
        TxtStatus.Text = status;
        StatusDot.Fill = dot;

        switch (_state)
        {
            case State.Connected when showingPeer:
                BtnConnect.Content = "התנתק";
                BtnConnect.Style = (Style)FindResource("DangerButton");
                BtnConnect.IsEnabled = true;
                break;
            case State.Connecting:
            case State.AwaitingApproval:
                BtnConnect.Content = "ביטול";
                BtnConnect.Style = (Style)FindResource("BaseButton");
                BtnConnect.IsEnabled = true;
                break;
            default:
                BtnConnect.Content = "התחבר";
                BtnConnect.Style = (Style)FindResource("PrimaryButton");
                BtnConnect.IsEnabled = _conversationKey != null && _state != State.IncomingRequest;
                break;
        }
        BtnRename.Visibility = _conversationKey != null ? Visibility.Visible : Visibility.Collapsed;

        bool canSend = CanSend;
        EditMessage.IsEnabled = canSend;
        BtnAttach.IsEnabled = canSend;
        BtnSend.IsEnabled = canSend && EditMessage.Text.Trim().Length > 0;
        TxtPlaceholder.Text = canSend ? "הודעה" : "התחבר כדי לשלוח הודעות";

        foreach (var d in _devices) d.IsConnected = _state == State.Connected && _peer?.Key == d.Key;
        _tray.Text = _state == State.Connected ? $"BT Chat - מחובר אל {Truncate(peerName, 30)}" : "BT Chat";
        UpdateEmptyState();
    }

    private static string Truncate(string s, int max) => s.Length <= max ? s : s.Substring(0, max);
}
