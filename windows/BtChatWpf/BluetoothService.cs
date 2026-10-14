using System;
using System.IO;
using System.Linq;
using System.Net.Sockets;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using InTheHand.Net;
using InTheHand.Net.Sockets;

namespace BtChatWpf;

/// <summary>Remote device: Key is the 12-hex-digit address (or "name:..." if unknown).</summary>
public sealed record PeerInfo(string Key, string Name, BluetoothAddress? Address);

public interface IIncomingFile
{
    Stream Output { get; }
    void Progress(long received);
    void Finished(bool success);
}

/// <summary>
/// Owns the single Bluetooth RFCOMM connection. Listens for incoming connections
/// or connects to a paired device. Events fire on background threads.
/// Handshake: the listening side must call Approve() or Reject(); the
/// connecting side waits for that answer.
/// </summary>
public sealed class BluetoothService
{
    public event Action<string>? ListenFailed;
    public event Action<PeerInfo>? IncomingRequest;
    public event Action<PeerInfo>? AwaitingApproval;
    public event Action<PeerInfo>? Connected;
    public event Action<PeerInfo, string>? ConnectFailed;
    public event Action<PeerInfo>? Rejected;
    public event Action<PeerInfo>? Disconnected;
    public event Action<string>? TextReceived;

    /// Called on the reader thread for each incoming file. Return null to drop it.
    public Func<string, long, IIncomingFile?>? FileIncoming;

    private readonly object _gate = new();
    private readonly SemaphoreSlim _writeLock = new(1, 1);
    private bool _wantListen;
    private BluetoothListener? _listener;
    private BluetoothClient? _connecting;
    private BluetoothClient? _client;
    private Stream? _stream;

    public bool HasConnection { get { lock (_gate) return _client != null || _connecting != null; } }

    public static string KeyOf(BluetoothAddress address) =>
        new string(address.ToString().Where(Uri.IsHexDigit).ToArray()).ToUpperInvariant();

    // ---------------------------------------------------------------- server

    public void StartListening()
    {
        lock (_gate)
        {
            if (_wantListen || _client != null || _connecting != null) return;
            _wantListen = true;
        }
        new Thread(ListenThread) { IsBackground = true, Name = "bt-listen" }.Start();
    }

    private void ListenThread()
    {
        BluetoothListener listener;
        try
        {
            listener = new BluetoothListener(BtProtocol.SppUuid);
            listener.Start();
        }
        catch (Exception ex)
        {
            lock (_gate) _wantListen = false;
            ListenFailed?.Invoke(ex.Message);
            return;
        }

        lock (_gate)
        {
            if (!_wantListen)
            {
                try { listener.Stop(); } catch { }
                return;
            }
            _listener = listener;
        }

        BluetoothClient? accepted = null;
        try { accepted = listener.AcceptBluetoothClient(); } catch { }

        bool stoppedOnPurpose;
        lock (_gate)
        {
            stoppedOnPurpose = !_wantListen;
            _wantListen = false;
            _listener = null;
        }
        try { listener.Stop(); } catch { }

        if (accepted == null)
        {
            if (!stoppedOnPurpose) ListenFailed?.Invoke("ההאזנה הופסקה");
            return;
        }
        lock (_gate)
        {
            if (stoppedOnPurpose || _client != null || _connecting != null)
            {
                try { accepted.Close(); } catch { }
                return;
            }
            Attach(accepted);
        }
        var peer = Identify(accepted);
        IncomingRequest?.Invoke(peer);
        ReadLoop(accepted, peer);
    }

    public void StopListening()
    {
        BluetoothListener? listener;
        lock (_gate)
        {
            _wantListen = false;
            listener = _listener;
            _listener = null;
        }
        try { listener?.Stop(); } catch { }
    }

    public void Approve() => _ = SendControlAsync(BtProtocol.TypeAccept);

    public void Reject()
    {
        Task.Run(async () =>
        {
            await SendControlAsync(BtProtocol.TypeReject);
            await Task.Delay(500); // let the answer arrive before closing
            Disconnect();
        });
    }

    // ---------------------------------------------------------------- client

    public void Connect(PeerInfo peer)
    {
        StopListening();
        var client = new BluetoothClient();
        lock (_gate) _connecting = client;
        new Thread(() =>
        {
            try
            {
                client.Connect(peer.Address!, BtProtocol.SppUuid);
            }
            catch (Exception ex)
            {
                bool cancelled;
                lock (_gate)
                {
                    cancelled = _connecting != client;
                    if (!cancelled) _connecting = null;
                }
                try { client.Close(); } catch { }
                ConnectFailed?.Invoke(peer, cancelled ? "בוטל" : ex.Message);
                return;
            }
            lock (_gate)
            {
                if (_connecting != client)
                {
                    try { client.Close(); } catch { }
                    ConnectFailed?.Invoke(peer, "בוטל");
                    return;
                }
                _connecting = null;
                Attach(client);
            }
            AwaitingApproval?.Invoke(peer);
            ReadLoop(client, peer);
        }) { IsBackground = true, Name = "bt-connect" }.Start();
    }

    // ---------------------------------------------------------------- shared

    private void Attach(BluetoothClient client)
    {
        _client = client;
        _stream = client.GetStream();
    }

    private static PeerInfo Identify(BluetoothClient client)
    {
        string name;
        try { name = client.RemoteMachineName ?? ""; } catch { name = ""; }

        BluetoothAddress? address = null;
        try
        {
            // 32feet exposes the underlying socket as "Client" on Windows.
            var socket = client.GetType().GetProperty("Client")?.GetValue(client) as Socket;
            if (socket?.RemoteEndPoint is BluetoothEndPoint ep) address = ep.Address;
        }
        catch { }

        if (address == null && name.Length > 0)
        {
            try
            {
                using var bc = new BluetoothClient();
                address = bc.PairedDevices.FirstOrDefault(d => d.DeviceName == name)?.DeviceAddress;
            }
            catch { }
        }
        if (name.Length == 0) name = address != null ? KeyOf(address) : "מכשיר לא ידוע";
        return new PeerInfo(address != null ? KeyOf(address) : "name:" + name, name, address);
    }

    private void ReadLoop(BluetoothClient client, PeerInfo peer)
    {
        try
        {
            var input = new BufferedStream(client.GetStream(), BtProtocol.Chunk);
            while (true)
            {
                byte type = BtProtocol.ReadByteStrict(input);
                int length = BtProtocol.ReadInt32BigEndian(input);
                switch (type)
                {
                    case BtProtocol.TypeText:
                        TextReceived?.Invoke(Encoding.UTF8.GetString(BtProtocol.ReadExact(input, length)));
                        break;
                    case BtProtocol.TypeFile:
                        ReceiveFile(input, length);
                        break;
                    case BtProtocol.TypeAccept:
                        Connected?.Invoke(peer);
                        break;
                    case BtProtocol.TypeReject:
                        Rejected?.Invoke(peer);
                        return;
                    default:
                        BtProtocol.Skip(input, length);
                        break;
                }
            }
        }
        catch
        {
            // connection closed
        }
        finally
        {
            lock (_gate)
            {
                if (_client == client)
                {
                    _client = null;
                    _stream = null;
                }
            }
            try { client.Close(); } catch { }
            Disconnected?.Invoke(peer);
        }
    }

    private void ReceiveFile(Stream input, int length)
    {
        int nameLength = (BtProtocol.ReadByteStrict(input) << 8) | BtProtocol.ReadByteStrict(input);
        string name = Encoding.UTF8.GetString(BtProtocol.ReadExact(input, nameLength));
        long size = (long)length - 2 - nameLength;

        IIncomingFile? target = null;
        try { target = FileIncoming?.Invoke(name, size); } catch { }

        var buffer = new byte[BtProtocol.Chunk];
        long received = 0;
        bool writeOk = target != null;
        try
        {
            while (received < size)
            {
                int n = input.Read(buffer, 0, (int)Math.Min(buffer.Length, size - received));
                if (n <= 0) throw new IOException("Stream closed");
                if (writeOk)
                {
                    try { target!.Output.Write(buffer, 0, n); }
                    catch { writeOk = false; } // keep draining so the stream stays in sync
                }
                received += n;
                target?.Progress(received);
            }
        }
        catch
        {
            if (target != null)
            {
                try { target.Output.Dispose(); } catch { }
                target.Finished(false);
            }
            throw;
        }
        if (target != null)
        {
            try { target.Output.Dispose(); } catch { writeOk = false; }
            target.Finished(writeOk);
        }
    }

    private async Task SendControlAsync(byte type)
    {
        await _writeLock.WaitAsync();
        try
        {
            var s = _stream;
            if (s != null) BtProtocol.WriteControl(s, type);
        }
        catch { }
        finally { _writeLock.Release(); }
    }

    public Task<bool> SendTextAsync(string text) => Task.Run(async () =>
    {
        await _writeLock.WaitAsync();
        try
        {
            var s = _stream;
            if (s == null) return false;
            BtProtocol.WriteText(s, text);
            return true;
        }
        catch { return false; }
        finally { _writeLock.Release(); }
    });

    public Task<bool> SendFileAsync(string path, string fileName, Action<long> progress) => Task.Run(async () =>
    {
        FileStream file;
        try { file = File.OpenRead(path); }
        catch { return false; }

        await _writeLock.WaitAsync();
        try
        {
            using (file)
            {
                var s = _stream;
                if (s == null) return false;
                try
                {
                    BtProtocol.WriteFile(s, fileName, file.Length, file, progress);
                    return true;
                }
                catch
                {
                    Disconnect(); // a half-written frame corrupts the stream
                    return false;
                }
            }
        }
        finally { _writeLock.Release(); }
    });

    public void Disconnect()
    {
        BluetoothClient? client, connecting;
        lock (_gate)
        {
            client = _client;
            connecting = _connecting;
            _client = null;
            _stream = null;
            _connecting = null;
        }
        try { client?.Close(); } catch { }
        try { connecting?.Close(); } catch { }
    }

    public void Shutdown()
    {
        StopListening();
        Disconnect();
    }
}
