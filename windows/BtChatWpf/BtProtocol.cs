using System;
using System.IO;
using System.Text;

namespace BtChatWpf;

/// <summary>
/// BT-Chat Protocol v2 (see /PROTOCOL.md at repo root).
/// Frame = [1 byte type][4 bytes length, BIG-ENDIAN][payload]
/// Must match the Android Kotlin implementation exactly.
/// </summary>
public static class BtProtocol
{
    // Standard SPP UUID - MUST match the Android side exactly.
    public static readonly Guid SppUuid = new("00001101-0000-1000-8000-00805F9B34FB");

    public const byte TypeText = 0x01;
    public const byte TypeFile = 0x02;
    public const byte TypeAccept = 0x04;
    public const byte TypeReject = 0x05;
    public const byte TypeWifiOffer = 0x06;
    public const byte TypeWifiPull = 0x07;
    public const byte TypeWifiDecline = 0x08;

    public static void WriteJson(Stream output, byte type, System.Text.Json.Nodes.JsonObject json)
    {
        var payload = Encoding.UTF8.GetBytes(json.ToJsonString());
        WriteHeader(output, type, payload.Length);
        output.Write(payload, 0, payload.Length);
        output.Flush();
    }

    public static System.Text.Json.Nodes.JsonObject ReadJson(Stream input, int length)
    {
        var bytes = ReadExact(input, length);
        try
        {
            return System.Text.Json.Nodes.JsonNode.Parse(Encoding.UTF8.GetString(bytes)) as System.Text.Json.Nodes.JsonObject
                   ?? new System.Text.Json.Nodes.JsonObject();
        }
        catch
        {
            return new System.Text.Json.Nodes.JsonObject();
        }
    }

    public const int Chunk = 16 * 1024;
    public const long MaxFileSize = int.MaxValue - 4096L;

    public static void WriteControl(Stream output, byte type)
    {
        WriteHeader(output, type, 0);
        output.Flush();
    }

    public static void WriteText(Stream output, string text)
    {
        var payload = Encoding.UTF8.GetBytes(text);
        WriteHeader(output, TypeText, payload.Length);
        output.Write(payload, 0, payload.Length);
        output.Flush();
    }

    /// Streams the file from <paramref name="input"/>; large files never sit fully in memory.
    public static void WriteFile(Stream output, string fileName, long size, Stream input, Action<long> progress)
    {
        if (fileName.Length > 200) fileName = fileName.Substring(0, 200);
        var nameBytes = Encoding.UTF8.GetBytes(fileName);
        WriteHeader(output, TypeFile, checked((int)(2 + nameBytes.Length + size)));
        output.Write(new[] { (byte)(nameBytes.Length >> 8), (byte)nameBytes.Length }, 0, 2);
        output.Write(nameBytes, 0, nameBytes.Length);

        var buffer = new byte[Chunk];
        long sent = 0;
        while (sent < size)
        {
            int n = input.Read(buffer, 0, (int)Math.Min(buffer.Length, size - sent));
            if (n <= 0) throw new IOException("הקובץ קצר מהצפוי");
            output.Write(buffer, 0, n);
            sent += n;
            progress(sent);
        }
        output.Flush();
    }

    private static void WriteHeader(Stream s, byte type, int length)
    {
        var header = new byte[]
        {
            type, (byte)(length >> 24), (byte)(length >> 16), (byte)(length >> 8), (byte)length
        };
        s.Write(header, 0, header.Length);
    }

    public static byte ReadByteStrict(Stream s)
    {
        int b = s.ReadByte();
        if (b == -1) throw new IOException("Stream closed");
        return (byte)b;
    }

    public static int ReadInt32BigEndian(Stream s)
    {
        var b = ReadExact(s, 4);
        return (b[0] << 24) | (b[1] << 16) | (b[2] << 8) | b[3];
    }

    public static byte[] ReadExact(Stream s, int count)
    {
        var buffer = new byte[count];
        int offset = 0;
        while (offset < count)
        {
            int read = s.Read(buffer, offset, count - offset);
            if (read <= 0) throw new IOException("Stream closed before frame complete");
            offset += read;
        }
        return buffer;
    }

    public static void Skip(Stream s, long count)
    {
        var buffer = new byte[Chunk];
        while (count > 0)
        {
            int read = s.Read(buffer, 0, (int)Math.Min(buffer.Length, count));
            if (read <= 0) throw new IOException("Stream closed");
            count -= read;
        }
    }
}
