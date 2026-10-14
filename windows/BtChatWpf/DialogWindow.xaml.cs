using System.Windows;
using System.Windows.Controls;

namespace BtChatWpf;

/// <summary>Small styled dialog: message + up to a few buttons, optional checkbox / text field.</summary>
public partial class DialogWindow : Window
{
    /// Index of the clicked button, or -1 if closed / cancelled.
    public int Result { get; private set; } = -1;
    public bool IsChecked => Check.IsChecked == true;
    public string InputText => Input.Text;

    public DialogWindow(string title, string message, string[] buttons,
        string? checkbox = null, string? input = null, int primaryIndex = 0)
    {
        InitializeComponent();
        TxtTitle.Text = title;
        TxtMessage.Text = message;
        TxtMessage.Visibility = string.IsNullOrEmpty(message) ? Visibility.Collapsed : Visibility.Visible;
        if (checkbox != null)
        {
            Check.Content = checkbox;
            Check.Visibility = Visibility.Visible;
        }
        if (input != null)
        {
            Input.Text = input;
            Input.Visibility = Visibility.Visible;
            Loaded += (_, _) => { Input.Focus(); Input.SelectAll(); };
        }
        for (int i = 0; i < buttons.Length; i++)
        {
            int index = i;
            var button = new Button
            {
                Content = buttons[i],
                Margin = new Thickness(8, 0, 0, 0),
                MinWidth = 84,
                IsDefault = i == primaryIndex,
                IsCancel = i == buttons.Length - 1 && buttons.Length > 1
            };
            if (i == primaryIndex) button.Style = (Style)FindResource("PrimaryButton");
            button.Click += (_, _) =>
            {
                Result = index;
                DialogResult = true;
            };
            ButtonsPanel.Children.Add(button);
        }
    }

    /// Shows a dialog owned by <paramref name="owner"/> (if it is visible) and returns the button index.
    public static int Ask(Window? owner, string title, string message, params string[] buttons)
    {
        var dialog = new DialogWindow(title, message, buttons);
        return dialog.ShowFor(owner);
    }

    public int ShowFor(Window? owner)
    {
        if (owner != null && owner.IsVisible) Owner = owner;
        else WindowStartupLocation = WindowStartupLocation.CenterScreen;
        ShowDialog();
        return Result;
    }

    /// Closes the dialog from code (e.g. the request was withdrawn).
    public void Dismiss()
    {
        Result = -1;
        if (IsVisible) Close();
    }
}
