using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Threading;
using System.Threading.Tasks;

namespace BtChatWpf;

/// <summary>
/// Fast path for big files: a direct TCP connection over the local network
/// (same router or a phone hotspot - no internet needed). Bluetooth is only used
/// to agree on address/port/token; see PROTOCOL.md.
/// The connecting side writes the 16-byte token first; the hosting side checks it.
/// Data always flows from the file sender to the receiver, then the receiver
/// answers with one byte: 1 = saved OK.
/// </summary>
public static class WifiTransfer
{
    public const long MinSize = 1L * 1024 * 1024;
    private const int ConnectTimeoutMs = 2500;
    private const int IoTimeoutMs = 30_000;
    private const int Buffer = 64 * 1024;

    public static List<string> LocalIpv4()
    {
        try
        {
            return NetworkInterface.GetAllNetworkInterfaces()
                .Where(n => n.OperationalStatus == OperationalStatus.Up &&
                            n.NetworkInterfaceType != NetworkInterfaceType.Loopback)
                .SelectMany(n => n.GetIPProperties().UnicastAddresses)
                .Select(a => a.Address)
                .Where(a => a.AddressFamily == AddressFamily.InterNetwork && !IPAddress.IsLoopback(a) &&
                            !a.ToString().StartsWith("169.254."))
                .Select(a => a.ToString())
                .Distinct()
                .ToList();
        }
        catch
        {
            return new List<string>();
        }
    }

    public static string ToHex(byte[] bytes) => Convert.ToHexString(bytes).ToLowerInvariant();

    public static byte[] FromHex(string? hex)
    {
        try { return Convert.FromHexString(hex ?? ""); }
        catch { return Array.Empty<byte>(); }
    }

    /// Tries every address in parallel and returns the first that connects (token already sent).
    public static async Task<TcpClient?> ConnectAnyAsync(IEnumerable<string> ips, int port, byte[] token)
    {
        if (port <= 0 || token.Length != 16) return null;
        var attempts = ips
            .Where(ip => IPAddress.TryParse(ip, out var a) && a.AddressFamily == AddressFamily.InterNetwork)
            .Select(ip => TryConnect(ip, port))
            .ToList();
        TcpClient? winner = null;
        while (attempts.Count > 0)
        {
            var finished = await Task.WhenAny(attempts);
            attempts.Remove(finished);
            var client = await finished;
            if (client == null) continue;
            winner = client;
            break;
        }
        // Close any connection that succeeds after the winner.
        foreach (var rest in attempts) _ = rest.ContinueWith(t => t.Result?.Dispose(), TaskScheduler.Default);
        if (winner == null) return null;
        try
        {
            var stream = winner.GetStream();
            stream.ReadTimeout = IoTimeoutMs;
            stream.WriteTimeout = IoTimeoutMs;
            stream.Write(token, 0, token.Length);
            stream.Flush();
            return winner;
        }
        catch
        {
            winner.Dispose();
            return null;
        }
    }

    private static async Task<TcpClient?> TryConnect(string ip, int port)
    {
        var client = new TcpClient(AddressFamily.InterNetwork) { NoDelay = true };
        try
        {
            using var cts = new CancellationTokenSource(ConnectTimeoutMs);
            await client.ConnectAsync(IPAddress.Parse(ip), port, cts.Token);
            return client;
        }
        catch
        {
            client.Dispose();
            return null;
        }
    }

    /// Listening socket on a random port, protected by a random token.
    public sealed class Host : IDisposable
    {
        private readonly TcpListener _listener;
        public byte[] Token { get; } = RandomNumberGenerator.GetBytes(16);
        public int Port => ((IPEndPoint)_listener.LocalEndpoint).Port;

        public Host()
        {
            _listener = new TcpListener(IPAddress.Any, 0);
            _listener.Start();
        }

        public async Task<TcpClient?> AcceptAsync(int timeoutMs)
        {
            using var cts = new CancellationTokenSource(timeoutMs);
            while (!cts.IsCancellationRequested)
            {
                TcpClient client;
                try { client = await _listener.AcceptTcpClientAsync(cts.Token); }
                catch { return null; }
                try
                {
                    client.NoDelay = true;
                    var stream = client.GetStream();
                    stream.ReadTimeout = 5000;
                    var got = BtProtocol.ReadExact(stream, 16);
                    if (got.SequenceEqual(Token))
                    {
                        stream.ReadTimeout = IoTimeoutMs;
                        stream.WriteTimeout = IoTimeoutMs;
                        return client;
                    }
                }
                catch { }
                client.Dispose();
            }
            return null;
        }

        public void Dispose()
        {
            try { _listener.Stop(); } catch { }
        }
    }

    /// Sender side: streams <paramref name="size"/> bytes and waits for the receiver's OK byte.
    public static bool Push(TcpClient client, Stream input, long size, Action<long> progress)
    {
        var stream = client.GetStream();
        var buffer = new byte[Buffer];
        long sent = 0;
        while (sent < size)
        {
            int n = input.Read(buffer, 0, (int)Math.Min(buffer.Length, size - sent));
            if (n <= 0) throw new IOException("הקובץ קצר מהצפוי");
            stream.Write(buffer, 0, n);
            sent += n;
            progress(sent);
        }
        stream.Flush();
        return stream.ReadByte() == 1;
    }

    /// Receiver side: reads <paramref name="size"/> bytes into the target and answers with an OK byte.
    public static void Pull(TcpClient client, long size, IIncomingFile target)
    {
        bool ok = true;
        try
        {
            var stream = client.GetStream();
            var buffer = new byte[Buffer];
            long received = 0;
            while (received < size)
            {
                int n = stream.Read(buffer, 0, (int)Math.Min(buffer.Length, size - received));
                if (n <= 0) throw new IOException("Stream closed");
                target.Output.Write(buffer, 0, n);
                received += n;
                target.Progress(received);
            }
            target.Output.Dispose();
            stream.WriteByte(1);
            stream.Flush();
        }
        catch
        {
            ok = false;
            try { target.Output.Dispose(); } catch { }
        }
        target.Finished(ok);
    }
}
