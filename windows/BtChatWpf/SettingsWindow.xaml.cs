using System;
using System.Collections.ObjectModel;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Reflection;
using System.Windows;
using System.Windows.Controls;
using Microsoft.Win32;

namespace BtChatWpf;

public partial class SettingsWindow : Window
{
    public sealed record TrustedEntry(string Key, string Label);

    private readonly AppSettings _settings;
    private readonly ObservableCollection<TrustedEntry> _trusted = new();
    private string? _folder;

    public SettingsWindow(AppSettings settings, Func<string, string> displayName)
    {
        InitializeComponent();
        _settings = settings;

        ChkNotifications.IsChecked = settings.Notifications;
        ChkTray.IsChecked = settings.CloseToTray;
        _folder = settings.SaveFolder;
        ShowFolder();

        CmbQuality.SelectedItem = CmbQuality.Items.OfType<ComboBoxItem>()
            .FirstOrDefault(i => (string)i.Tag == settings.ImageQuality) ?? CmbQuality.Items[0];

        foreach (var key in settings.Trusted)
            _trusted.Add(new TrustedEntry(key, $"{displayName(key)}  ({DeviceItem.FormatKey(key)})"));
        ListTrusted.ItemsSource = _trusted;

        TxtVersion.Text = "BT Chat גרסה " + (Assembly.GetExecutingAssembly().GetName().Version?.ToString(3) ?? "");
    }

    private string EffectiveFolder => string.IsNullOrWhiteSpace(_folder) ? AppPaths.DefaultSaveFolder : _folder!;

    private void ShowFolder() => TxtFolder.Text = EffectiveFolder;

    private void BtnChangeFolder_Click(object sender, RoutedEventArgs e)
    {
        var dialog = new OpenFolderDialog { Title = "בחר תיקייה לקבצים שמתקבלים" };
        if (Directory.Exists(EffectiveFolder)) dialog.InitialDirectory = EffectiveFolder;
        if (dialog.ShowDialog(this) == true)
        {
            _folder = dialog.FolderName;
            ShowFolder();
        }
    }

    private void BtnDefaultFolder_Click(object sender, RoutedEventArgs e)
    {
        _folder = null;
        ShowFolder();
    }

    private void BtnOpenFolder_Click(object sender, RoutedEventArgs e)
    {
        try
        {
            Directory.CreateDirectory(EffectiveFolder);
            Process.Start(new ProcessStartInfo(EffectiveFolder) { UseShellExecute = true });
        }
        catch { }
    }

    private void BtnRemoveTrusted_Click(object sender, RoutedEventArgs e)
    {
        if (ListTrusted.SelectedItem is TrustedEntry entry) _trusted.Remove(entry);
    }

    private void BtnSave_Click(object sender, RoutedEventArgs e)
    {
        _settings.Notifications = ChkNotifications.IsChecked == true;
        _settings.CloseToTray = ChkTray.IsChecked == true;
        _settings.SaveFolder = _folder;
        _settings.ImageQuality = (CmbQuality.SelectedItem as ComboBoxItem)?.Tag as string ?? "ask";
        _settings.Trusted = _trusted.Select(t => t.Key).ToList();
        DialogResult = true;
    }
}
