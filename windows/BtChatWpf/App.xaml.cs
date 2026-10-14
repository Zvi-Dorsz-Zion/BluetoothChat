using System.Threading;
using System.Windows;

namespace BtChatWpf;

public partial class App : Application
{
    private Mutex? _singleInstance;
    private EventWaitHandle? _showSignal;

    protected override void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);

        // Only one copy may listen on Bluetooth. A second launch just brings the first to front.
        _singleInstance = new Mutex(true, "BtChat.SingleInstance", out bool isFirst);
        _showSignal = new EventWaitHandle(false, EventResetMode.AutoReset, "BtChat.ShowWindow");
        if (!isFirst)
        {
            _showSignal.Set();
            Shutdown();
            return;
        }

        var window = new MainWindow();
        MainWindow = window;
        window.Show();

        var signal = _showSignal;
        new Thread(() =>
        {
            while (true)
            {
                signal.WaitOne();
                Dispatcher.BeginInvoke(new Action(window.BringToFront));
            }
        }) { IsBackground = true, Name = "show-signal" }.Start();
    }
}
