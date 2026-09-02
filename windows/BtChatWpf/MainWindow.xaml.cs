using System;
using System.IO;
using System.Windows;
using System.Windows.Input;
using Microsoft.Win32;
using InTheHand.Net.Sockets;

namespace BtChatWpf;

public partial class MainWindow : Window
{
    private readonly BluetoothService _bt = new();

    public MainWindow()
    {
        InitializeComponent();

        _bt.Connected += name => Dispatcher.Invoke(() => TxtStatus.Text = $"מחובר אל: {name}");
        _bt.Disconnected += () => Dispatcher.Invoke(() => TxtStatus.Text = "מנותק");
        _bt.TextReceived += text => Dispatcher.Invoke(() => AppendChat($"הם: {text}"));
        _bt.FileReceived += (name, bytes) => Dispatcher.Invoke(() => SaveIncomingFile(name, bytes));
        _bt.Error += msg => Dispatcher.Invoke(() =>
        {
            TxtStatus.Text = "שגיאה";
            MessageBox.Show(msg, "שגיאה", MessageBoxButton.OK, MessageBoxImage.Warning);
        });
    }

    private void BtnListen_Click(object sender, RoutedEventArgs e)
    {
        TxtStatus.Text = "ממתין לחיבור נכנס...";
        _bt.StartServer();
    }

    private void BtnPickDevice_Click(object sender, RoutedEventArgs e)
    {
        var devices = BluetoothService.GetPairedDevices();
        if (devices.Length == 0)
        {
            MessageBox.Show("אין מכשירים מזווגים. זווג קודם דרך הגדרות בלוטוס של Windows.",
                "אין מכשירים", MessageBoxButton.OK, MessageBoxImage.Information);
            return;
        }

        var picker = new DevicePickerWindow(devices) { Owner = this };
        if (picker.ShowDialog() == true && picker.SelectedDevice != null)
        {
            TxtStatus.Text = $"מתחבר אל {picker.SelectedDevice.DeviceName}...";
            _bt.ConnectToDevice(picker.SelectedDevice);
        }
    }

    private void BtnSend_Click(object sender, RoutedEventArgs e) => SendCurrentText();

    private void EditMessage_KeyDown(object sender, KeyEventArgs e)
    {
        if (e.Key == Key.Enter) SendCurrentText();
    }

    private void SendCurrentText()
    {
        var text = EditMessage.Text.Trim();
        if (text.Length == 0) return;
        _bt.SendText(text);
        AppendChat($"אני: {text}");
        EditMessage.Clear();
    }

    private void BtnAttach_Click(object sender, RoutedEventArgs e)
    {
        var dialog = new OpenFileDialog { Title = "בחר קובץ לשליחה" };
        if (dialog.ShowDialog() == true)
        {
            var bytes = File.ReadAllBytes(dialog.FileName);
            var name = Path.GetFileName(dialog.FileName);
            _bt.SendFile(name, bytes);
            AppendChat($"אני (קובץ): {name}");
        }
    }

    private void SaveIncomingFile(string fileName, byte[] bytes)
    {
        var dir = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.MyDocuments), "BtChat received");
        Directory.CreateDirectory(dir);
        var path = Path.Combine(dir, fileName);
        File.WriteAllBytes(path, bytes);
        AppendChat($"הם (קובץ): {fileName} נשמר ב-{path}");
    }

    private void AppendChat(string line)
    {
        TxtChat.Text += line + Environment.NewLine;
        ChatScroll.ScrollToEnd();
    }

    protected override void OnClosed(EventArgs e)
    {
        base.OnClosed(e);
        _bt.Stop();
    }
}
