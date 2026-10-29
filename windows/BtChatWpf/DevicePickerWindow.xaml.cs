using System;
using System.Collections.Generic;
using System.Collections.ObjectModel;
using System.Diagnostics;
using System.Linq;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using InTheHand.Net.Bluetooth;
using InTheHand.Net.Sockets;

namespace BtChatWpf;

/// <summary>
/// Choose a device to start chatting with: devices already paired in Windows that were
/// never used with BT Chat (connect directly), or nearby devices found by searching (pair first).
/// </summary>
public partial class DevicePickerWindow : Window
{
    public sealed record Found(string Name, string Subtitle, BluetoothDeviceInfo Info, bool IsPaired);

    /// The chosen device, paired and ready to connect.
    public BluetoothDeviceInfo? PairedDevice { get; private set; }

    private readonly ObservableCollection<Found> _items = new();
    private bool _busy;

    public DevicePickerWindow(IEnumerable<BluetoothDeviceInfo> otherPaired)
    {
        InitializeComponent();
        foreach (var d in otherPaired.OrderBy(d => d.DeviceName))
        {
            _items.Add(new Found(
                string.IsNullOrWhiteSpace(d.DeviceName) ? "(ללא שם)" : d.DeviceName,
                "מזווג · הקש פעמיים כדי להתחבר", d, true));
        }
        ListFound.ItemsSource = _items;
        if (_items.Count > 0) ListFound.SelectedIndex = 0;
        UpdateButton();
        Loaded += (_, _) => Search();
    }

    private async void Search()
    {
        if (_busy) return;
        _busy = true;
        SetBusy(true, "מחפש מכשירים חדשים בסביבה... (עד כחצי דקה)");
        foreach (var old in _items.Where(i => !i.IsPaired).ToList()) _items.Remove(old);
        try
        {
            var devices = await Task.Run(() =>
            {
                using var client = new BluetoothClient();
                return client.DiscoverDevices().Where(d => !d.Authenticated).ToList();
            });
            foreach (var d in devices)
            {
                _items.Add(new Found(
                    string.IsNullOrWhiteSpace(d.DeviceName) ? "(ללא שם)" : d.DeviceName,
                    "חדש · " + DeviceItem.FormatKey(BluetoothService.KeyOf(d.DeviceAddress)), d, false));
            }
            if (ListFound.SelectedItem == null && _items.Count > 0) ListFound.SelectedIndex = 0;
            SetBusy(false, devices.Count == 0
                ? "לא נמצאו מכשירים חדשים. ודא שמסך ה-Bluetooth פתוח בטלפון ולחץ \"חפש שוב\"."
                : $"נמצאו {devices.Count} מכשירים חדשים.");
        }
        catch (Exception ex)
        {
            SetBusy(false, "החיפוש נכשל: " + ex.Message);
        }
        _busy = false;
        UpdateButton();
    }

    private void SetBusy(bool busy, string text)
    {
        TxtState.Text = text;
        Progress.Visibility = busy ? Visibility.Visible : Visibility.Hidden;
        BtnSearch.IsEnabled = !busy;
    }

    private void UpdateButton()
    {
        var selected = ListFound.SelectedItem as Found;
        BtnPair.Content = selected?.IsPaired == true ? "התחבר" : "צמד והתחבר";
        BtnPair.IsEnabled = selected != null && (!_busy || selected.IsPaired);
    }

    private async void Choose()
    {
        if (ListFound.SelectedItem is not Found found) return;
        if (found.IsPaired)
        {
            PairedDevice = found.Info;
            DialogResult = true;
            return;
        }
        if (_busy) return;
        _busy = true;
        SetBusy(true, $"מצמד עם {found.Name}... אשר את הקוד בטלפון ובחלון של Windows אם מופיע.");
        BtnPair.IsEnabled = false;
        bool ok;
        try
        {
            ok = await Task.Run(() => BluetoothSecurity.PairRequest(found.Info.DeviceAddress, null));
        }
        catch
        {
            ok = false;
        }
        _busy = false;
        if (ok)
        {
            PairedDevice = found.Info;
            DialogResult = true;
            return;
        }
        SetBusy(false, "הצימוד נכשל. נסה שוב, או צמד דרך הגדרות ה-Bluetooth של Windows (הקישור למטה).");
        UpdateButton();
    }

    private void BtnSearch_Click(object sender, RoutedEventArgs e) => Search();

    private void BtnPair_Click(object sender, RoutedEventArgs e) => Choose();

    private void ListFound_MouseDoubleClick(object sender, MouseButtonEventArgs e) => Choose();

    private void ListFound_SelectionChanged(object sender, SelectionChangedEventArgs e) => UpdateButton();

    private void LinkWindowsSettings_Click(object sender, RoutedEventArgs e)
    {
        try { Process.Start(new ProcessStartInfo("ms-settings:bluetooth") { UseShellExecute = true }); }
        catch { }
    }
}
