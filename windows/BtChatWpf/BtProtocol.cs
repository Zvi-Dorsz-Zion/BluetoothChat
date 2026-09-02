using System;
using System.IO;
using System.Text;

namespace BtChatWpf;

/// <summary>
/// BT-Chat Protocol v1 (see /PROTOCOL.md at repo root).
/// Frame = [1 byte type][4 bytes length, BIG-ENDIAN][payload]
/// Must match the Android Kotlin implementation exactly.
/// </summary>
public static class BtProtocol
{
    // Standard SPP UUID - MUST match the Android side exactly.
    public static readonly Guid SppUuid = new("00001101-0000-1000-8000-00805F9B34FB");

    public const byte TypeText = 0x01;
    public const byte TypeFile = 0x02;

    public abstract record Message
    {
        public sealed record Text(string Value) : Message;
        public sealed record FileReceived(string FileName, byte[] Bytes) : Message;
    }

    public static void SendText(Stream output, string text)
    {
        var payload = Encoding.UTF8.GetBytes(text);
        WriteFrame(output, TypeText, payload);
    }

    public static void SendFile(Stream output, string fileName, byte[] fileBytes)
    {
        var nameBytes = Encoding.UTF8.GetBytes(fileName);
        using var buffer = new MemoryStream();
        WriteUInt16BigEndian(buffer, (ushort)nameBytes.Length);
        buffer.Write(nameBytes, 0, nameBytes.Length);
        buffer.Write(fileBytes, 0, fileBytes.Length);
        WriteFrame(output, TypeFile, buffer.ToArray());
    }

    private static void WriteFrame(Stream output, byte type, byte[] payload)
    {
        lock (output)
        {
            output.WriteByte(type);
            WriteInt32BigEndian(output, payload.Length);
            output.Write(payload, 0, payload.Length);
            output.Flush();
        }
    }

    /// Blocking read of exactly one frame. Throws when the stream closes/errors.
    public static Message ReadNextMessage(Stream input)
    {
        byte type = ReadByteStrict(input);
        int length = ReadInt32BigEndian(input);
        byte[] payload = ReadExact(input, length);

        return type switch
        {
            TypeText => new Message.Text(Encoding.UTF8.GetString(payload)),
            TypeFile => ParseFilePayload(payload),
            _ => throw new InvalidDataException($"Unknown frame type: {type}")
        };
    }

    private static Message.FileReceived ParseFilePayload(byte[] payload)
    {
        int nameLen = (payload[0] << 8) | payload[1];
        string fileName = Encoding.UTF8.GetString(payload, 2, nameLen);
        byte[] fileBytes = new byte[payload.Length - 2 - nameLen];
        Array.Copy(payload, 2 + nameLen, fileBytes, 0, fileBytes.Length);
        return new Message.FileReceived(fileName, fileBytes);
    }

    // ---- Big-endian helpers (Stream has no built-in endianness control) ----

    private static void WriteInt32BigEndian(Stream s, int value)
    {
        s.WriteByte((byte)(value >> 24));
        s.WriteByte((byte)(value >> 16));
        s.WriteByte((byte)(value >> 8));
        s.WriteByte((byte)value);
    }

    private static void WriteUInt16BigEndian(Stream s, ushort value)
    {
        s.WriteByte((byte)(value >> 8));
        s.WriteByte((byte)value);
    }

    private static int ReadInt32BigEndian(Stream s)
    {
        var b = ReadExact(s, 4);
        return (b[0] << 24) | (b[1] << 16) | (b[2] << 8) | b[3];
    }

    private static byte ReadByteStrict(Stream s)
    {
        int b = s.ReadByte();
        if (b == -1) throw new IOException("Stream closed");
        return (byte)b;
    }

    private static byte[] ReadExact(Stream s, int count)
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
}
