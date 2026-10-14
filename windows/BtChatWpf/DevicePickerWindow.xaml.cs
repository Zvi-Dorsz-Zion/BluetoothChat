using System;
using System.Diagnostics;
using System.Linq;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Input;
using InTheHand.Net.Bluetooth;
using InTheHand.Net.Sockets;

namespace BtChatWpf;

/// <summary>Searches for nearby, not-yet-paired devices and pairs the chosen one.</summary>
public partial class DevicePickerWindow : Window
{
    public sealed record Found(string Name, string Address, BluetoothDeviceInfo Info);

    public BluetoothDeviceInfo? PairedDevice { get; private set; }

    private bool _busy;

    public DevicePickerWindow()
    {
        InitializeComponent();
        Loaded += (_, _) => Search();
    }

    private async void Search()
    {
        if (_busy) return;
        _busy = true;
        SetBusy(true, "מחפש מכשירים... (עד כחצי דקה)");
        ListFound.ItemsSource = null;
        try
        {
            var devices = await Task.Run(() =>
            {
                using var client = new BluetoothClient();
                return client.DiscoverDevices().Where(d => !d.Authenticated).ToList();
            });
            var found = devices
                .Select(d => new Found(
                    string.IsNullOrWhiteSpace(d.DeviceName) ? "(ללא שם)" : d.DeviceName,
                    DeviceItem.FormatKey(BluetoothService.KeyOf(d.DeviceAddress)), d))
                .ToList();
            ListFound.ItemsSource = found;
            if (found.Count > 0) ListFound.SelectedIndex = 0;
            SetBusy(false, found.Count == 0
                ? "לא נמצאו מכשירים. ודא שמסך ה-Bluetooth פתוח בטלפון ולחץ \"חפש שוב\"."
                : $"נמצאו {found.Count} מכשירים. בחר מכשיר ולחץ \"צמד והתחבר\".");
        }
        catch (Exception ex)
        {
            SetBusy(false, "החיפוש נכשל: " + ex.Message);
        }
        _busy = false;
    }

    private void SetBusy(bool busy, string text)
    {
        TxtState.Text = text;
        Progress.Visibility = busy ? Visibility.Visible : Visibility.Hidden;
        BtnSearch.IsEnabled = !busy;
        BtnPair.IsEnabled = !busy;
    }

    private async void Pair()
    {
        if (_busy || ListFound.SelectedItem is not Found found) return;
        _busy = true;
        SetBusy(true, $"מצמד עם {found.Name}... אשר את הקוד בטלפון ובחלון של Windows אם מופיע.");
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
    }

    private void BtnSearch_Click(object sender, RoutedEventArgs e) => Search();

    private void BtnPair_Click(object sender, RoutedEventArgs e) => Pair();

    private void ListFound_MouseDoubleClick(object sender, MouseButtonEventArgs e) => Pair();

    private void LinkWindowsSettings_Click(object sender, RoutedEventArgs e)
    {
        try { Process.Start(new ProcessStartInfo("ms-settings:bluetooth") { UseShellExecute = true }); }
        catch { }
    }
}
