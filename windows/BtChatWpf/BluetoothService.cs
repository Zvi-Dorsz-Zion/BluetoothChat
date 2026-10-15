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

    /// Called on a background thread for each incoming file (name, size, viaWifi). Return null to drop it.
    public Func<string, long, bool, IIncomingFile?>? FileIncoming;

    /// Whether big files may go over the local Wi-Fi network.
    public Func<bool> WifiEnabled = () => true;

    private readonly object _gate = new();
    private readonly SemaphoreSlim _writeLock = new(1, 1);
    private readonly SemaphoreSlim _fileLock = new(1, 1);
    private readonly System.Collections.Concurrent.ConcurrentDictionary<long, TaskCompletionSource<(byte Type, System.Text.Json.Nodes.JsonObject Json)>> _wifiAnswers = new();
    private long _offerIds = DateTime.UtcNow.Ticks;
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
                    case BtProtocol.TypeWifiOffer:
                        var offer = BtProtocol.ReadJson(input, length);
                        _ = Task.Run(async () => { try { await ReceiveWifiOfferAsync(offer); } catch { } });
                        break;
                    case BtProtocol.TypeWifiPull:
                    case BtProtocol.TypeWifiDecline:
                        var answer = BtProtocol.ReadJson(input, length);
                        long answerId = (long?)answer["id"] ?? 0;
                        if (_wifiAnswers.TryGetValue(answerId, out var waiting)) waiting.TrySetResult((type, answer));
                        break;
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
        try { target = FileIncoming?.Invoke(name, size, false); } catch { }

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

    /// Sends a file: over Wi-Fi when possible (big files), otherwise over Bluetooth.
    /// <paramref name="onWifi"/> is called once the Wi-Fi connection is up and data starts flowing.
    public Task<bool> SendFileAsync(string path, string fileName, Action<long> progress, Action? onWifi = null) => Task.Run(async () =>
    {
        await _fileLock.WaitAsync(); // one file at a time; text keeps flowing during Wi-Fi transfers
        try
        {
            long size;
            try { size = new FileInfo(path).Length; }
            catch { return false; }
            if (_stream == null) return false;

            if (size >= WifiTransfer.MinSize && WifiEnabled())
            {
                bool? viaWifi = null;
                try { viaWifi = await SendViaWifiAsync(path, fileName, size, progress, onWifi); } catch { }
                if (viaWifi.HasValue) return viaWifi.Value;
            }
            return await SendViaBluetoothAsync(path, fileName, progress);
        }
        finally { _fileLock.Release(); }
    });

    private async Task<bool> SendViaBluetoothAsync(string path, string fileName, Action<long> progress)
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
    }

    /// Windows never hosts as the sender (avoids the firewall prompt): it asks the
    /// receiver to host (offer without address) and connects to it.
    /// Returns null if no Wi-Fi link could be set up (caller falls back to Bluetooth).
    private async Task<bool?> SendViaWifiAsync(string path, string fileName, long size, Action<long> progress, Action? onWifi)
    {
        long id = Interlocked.Increment(ref _offerIds);
        var answer = new TaskCompletionSource<(byte Type, System.Text.Json.Nodes.JsonObject Json)>(
            TaskCreationOptions.RunContinuationsAsynchronously);
        _wifiAnswers[id] = answer;
        try
        {
            var offer = new System.Text.Json.Nodes.JsonObject { ["id"] = id, ["name"] = fileName, ["size"] = size };
            if (!await WriteJsonAsync(BtProtocol.TypeWifiOffer, offer)) return null;

            var first = await Task.WhenAny(answer.Task, Task.Delay(20_000));
            if (first != answer.Task) return null;
            var (type, json) = answer.Task.Result;
            if (type != BtProtocol.TypeWifiPull) return null;

            var ips = (json["ips"] as System.Text.Json.Nodes.JsonArray)?.Select(n => (string?)n ?? "").ToList()
                      ?? new List<string>();
            using var client = await WifiTransfer.ConnectAnyAsync(ips, (int?)json["port"] ?? 0,
                WifiTransfer.FromHex((string?)json["token"]));
            if (client == null)
            {
                await WriteJsonAsync(BtProtocol.TypeWifiDecline, new System.Text.Json.Nodes.JsonObject { ["id"] = id });
                return null;
            }
            onWifi?.Invoke();
            try
            {
                using var file = File.OpenRead(path);
                return WifiTransfer.Push(client, file, size, progress);
            }
            catch
            {
                return false;
            }
        }
        finally
        {
            _wifiAnswers.TryRemove(id, out _);
        }
    }

    /// Receiver side of a Wi-Fi offer: connect to the sender, or host and ask it to connect to us.
    private async Task ReceiveWifiOfferAsync(System.Text.Json.Nodes.JsonObject offer)
    {
        long id = (long?)offer["id"] ?? 0;
        string name = (string?)offer["name"] ?? "file";
        long size = (long?)offer["size"] ?? 0;
        var decline = new System.Text.Json.Nodes.JsonObject { ["id"] = id };
        if (!WifiEnabled() || size <= 0)
        {
            await WriteJsonAsync(BtProtocol.TypeWifiDecline, decline);
            return;
        }

        var ips = (offer["ips"] as System.Text.Json.Nodes.JsonArray)?.Select(n => (string?)n ?? "").ToList()
                  ?? new List<string>();
        TcpClient? client = null;
        if (ips.Count > 0)
            client = await WifiTransfer.ConnectAnyAsync(ips, (int?)offer["port"] ?? 0, WifiTransfer.FromHex((string?)offer["token"]));

        if (client == null)
        {
            try
            {
                using var host = new WifiTransfer.Host();
                var pull = new System.Text.Json.Nodes.JsonObject
                {
                    ["id"] = id,
                    ["port"] = host.Port,
                    ["ips"] = new System.Text.Json.Nodes.JsonArray(WifiTransfer.LocalIpv4().Select(ip => (System.Text.Json.Nodes.JsonNode?)ip).ToArray()),
                    ["token"] = WifiTransfer.ToHex(host.Token)
                };
                if (await WriteJsonAsync(BtProtocol.TypeWifiPull, pull)) client = await host.AcceptAsync(8000);
            }
            catch { client = null; }
        }

        if (client == null)
        {
            await WriteJsonAsync(BtProtocol.TypeWifiDecline, decline);
            return;
        }
        using (client)
        {
            IIncomingFile? target = null;
            try { target = FileIncoming?.Invoke(name, size, true); } catch { }
            if (target != null) WifiTransfer.Pull(client, size, target);
        }
    }

    private async Task<bool> WriteJsonAsync(byte type, System.Text.Json.Nodes.JsonObject json)
    {
        await _writeLock.WaitAsync();
        try
        {
            var s = _stream;
            if (s == null) return false;
            BtProtocol.WriteJson(s, type, json);
            return true;
        }
        catch { return false; }
        finally { _writeLock.Release(); }
    }

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
