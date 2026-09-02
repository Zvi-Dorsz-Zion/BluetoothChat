using System;
using System.IO;
using System.Threading;
using System.Threading.Tasks;
using InTheHand.Net;
using InTheHand.Net.Bluetooth;
using InTheHand.Net.Sockets;

namespace BtChatWpf;

/// <summary>
/// Wraps a single Bluetooth RFCOMM (SPP) connection. Can act as SERVER (listen)
/// or CLIENT (connect to a paired device) - mirrors the Android BluetoothService.
/// </summary>
public class BluetoothService
{
    public event Action<string>? Connected;
    public event Action? Disconnected;
    public event Action<string>? TextReceived;
    public event Action<string, byte[]>? FileReceived;
    public event Action<string>? Error;

    private BluetoothListener? _listener;
    private BluetoothClient? _client;
    private NetworkStream? _stream;
    private volatile bool _running;
    private CancellationTokenSource? _cts;

    public static BluetoothDeviceInfo[] GetPairedDevices()
    {
        using var client = new BluetoothClient();
        var list = new System.Collections.Generic.List<BluetoothDeviceInfo>();
        foreach (var d in client.PairedDevices) list.Add(d);
        return list.ToArray();
    }

    public void StartServer()
    {
        Stop();
        _running = true;
        _cts = new CancellationTokenSource();
        Task.Run(() =>
        {
            try
            {
                _listener = new BluetoothListener(BtProtocol.SppUuid);
                _listener.Start();
                var client = _listener.AcceptBluetoothClient(); // blocks until a peer connects
                _listener.Stop();
                Attach(client);
            }
            catch (Exception ex)
            {
                if (_running) Error?.Invoke($"שגיאת שרת: {ex.Message}");
            }
        }, _cts.Token);
    }

    public void ConnectToDevice(BluetoothDeviceInfo device)
    {
        Stop();
        _running = true;
        _cts = new CancellationTokenSource();
        Task.Run(() =>
        {
            try
            {
                var client = new BluetoothClient();
                client.Connect(device.DeviceAddress, BtProtocol.SppUuid);
                Attach(client);
            }
            catch (Exception ex)
            {
                if (_running) Error?.Invoke($"שגיאת חיבור: {ex.Message}");
            }
        }, _cts.Token);
    }

    private void Attach(BluetoothClient client)
    {
        _client = client;
        _stream = client.GetStream();
        Connected?.Invoke(client.RemoteMachineName ?? "מכשיר לא ידוע");
        ReadLoop(_stream);
    }

    private void ReadLoop(Stream stream)
    {
        try
        {
            while (_running)
            {
                var msg = BtProtocol.ReadNextMessage(stream);
                switch (msg)
                {
                    case BtProtocol.Message.Text t:
                        TextReceived?.Invoke(t.Value);
                        break;
                    case BtProtocol.Message.FileReceived f:
                        FileReceived?.Invoke(f.FileName, f.Bytes);
                        break;
                }
            }
        }
        catch (Exception ex)
        {
            if (_running) Error?.Invoke($"החיבור נותק: {ex.Message}");
        }
        finally
        {
            Disconnected?.Invoke();
        }
    }

    public void SendText(string text)
    {
        if (_stream == null) { Error?.Invoke("אין חיבור פעיל"); return; }
        Task.Run(() =>
        {
            try { BtProtocol.SendText(_stream, text); }
            catch (Exception ex) { Error?.Invoke($"שגיאה בשליחה: {ex.Message}"); }
        });
    }

    public void SendFile(string fileName, byte[] bytes)
    {
        if (_stream == null) { Error?.Invoke("אין חיבור פעיל"); return; }
        Task.Run(() =>
        {
            try { BtProtocol.SendFile(_stream, fileName, bytes); }
            catch (Exception ex) { Error?.Invoke($"שגיאה בשליחת קובץ: {ex.Message}"); }
        });
    }

    public void Stop()
    {
        _running = false;
        try { _listener?.Stop(); } catch { /* ignore */ }
        try { _stream?.Close(); } catch { /* ignore */ }
        try { _client?.Close(); } catch { /* ignore */ }
        _stream = null;
        _client = null;
        _listener = null;
    }
}
