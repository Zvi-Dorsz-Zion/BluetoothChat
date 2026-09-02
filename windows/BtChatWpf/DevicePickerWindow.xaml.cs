using System.Windows;
using InTheHand.Net.Sockets;

namespace BtChatWpf;

public partial class DevicePickerWindow : Window
{
    public BluetoothDeviceInfo? SelectedDevice { get; private set; }

    public DevicePickerWindow(BluetoothDeviceInfo[] devices)
    {
        InitializeComponent();
        ListDevices.ItemsSource = devices;
        if (devices.Length > 0) ListDevices.SelectedIndex = 0;
    }

    private void BtnConnect_Click(object sender, RoutedEventArgs e)
    {
        SelectedDevice = ListDevices.SelectedItem as BluetoothDeviceInfo;
        DialogResult = SelectedDevice != null;
    }

    private void BtnCancel_Click(object sender, RoutedEventArgs e)
    {
        DialogResult = false;
    }
}
