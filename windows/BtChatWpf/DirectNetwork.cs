using System;
using System.IO;
using System.Linq;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using System.Security;
using System.Threading.Tasks;

namespace BtChatWpf;

/// <summary>Small diagnostic log: %APPDATA%\BtChat\log.txt (kept under ~1MB).</summary>
public static class AppLog
{
    private static readonly object Lock = new();
    private static string FilePath => Path.Combine(AppPaths.DataDir, "log.txt");

    public static void Write(string message)
    {
        lock (Lock)
        {
            try
            {
                Directory.CreateDirectory(AppPaths.DataDir);
                var info = new FileInfo(FilePath);
                if (info.Exists && info.Length > 1_000_000) File.Move(FilePath, FilePath + ".old", true);
                File.AppendAllText(FilePath, $"{DateTime.Now:yyyy-MM-dd HH:mm:ss.fff}  {message}{Environment.NewLine}");
            }
            catch { }
        }
    }
}

/// <summary>
/// "Direct network": joins the phone's temporary hotspot (name + password arrive over
/// Bluetooth) and afterwards returns to the network the PC was on before.
/// Uses the native Wi-Fi API (wlanapi.dll) - no admin rights, independent of the UI language.
/// </summary>
public sealed class DirectNetwork
{
    private readonly object _lock = new();
    private string? _joinedSsid;
    private string? _previousProfile;
    private Guid _interface;

    public bool IsJoined { get { lock (_lock) return _joinedSsid != null; } }

    /// Connects to the hotspot and waits (up to ~30s) until the PC has an address on it.
    public Task<bool> JoinAsync(string ssid, string password) => Task.Run(async () =>
    {
        try
        {
            using var wlan = new Wlan();
            var iface = wlan.WifiInterface() ?? throw new InvalidOperationException("לא נמצא מתאם Wi-Fi");
            var current = wlan.CurrentProfile(iface);
            if (current == ssid && HasAddress(iface))
            {
                lock (_lock) { _joinedSsid = ssid; _interface = iface; }
                return true;
            }
            lock (_lock)
            {
                if (_joinedSsid == null) _previousProfile = current; // remember where to go back to
                _interface = iface;
            }
            AppLog.Write($"direct: joining '{ssid}' (was on '{current ?? "-"}')");
            wlan.SetProfile(iface, ProfileXml(ssid, password));
            wlan.Connect(iface, ssid);
            for (int i = 0; i < 60; i++)
            {
                await Task.Delay(500);
                if (wlan.CurrentProfile(iface) == ssid && HasAddress(iface))
                {
                    lock (_lock) _joinedSsid = ssid;
                    AppLog.Write("direct: joined");
                    return true;
                }
            }
            AppLog.Write("direct: join timed out");
            Cleanup(wlan, iface, ssid);
            return false;
        }
        catch (Exception ex)
        {
            AppLog.Write("direct: join failed: " + ex.Message);
            return false;
        }
    });

    /// Leaves the hotspot, deletes its temporary profile and reconnects to the previous network.
    public void Leave()
    {
        string? ssid;
        Guid iface;
        lock (_lock)
        {
            ssid = _joinedSsid;
            iface = _interface;
            _joinedSsid = null;
        }
        if (ssid == null) return;
        try
        {
            using var wlan = new Wlan();
            Cleanup(wlan, iface, ssid);
            AppLog.Write("direct: left hotspot");
        }
        catch (Exception ex)
        {
            AppLog.Write("direct: leave failed: " + ex.Message);
        }
    }

    private void Cleanup(Wlan wlan, Guid iface, string ssid)
    {
        try { wlan.DeleteProfile(iface, ssid); } catch { }
        string? previous;
        lock (_lock)
        {
            previous = _previousProfile;
            _previousProfile = null;
        }
        if (!string.IsNullOrEmpty(previous) && previous != ssid)
        {
            try { wlan.Connect(iface, previous); } catch { }
        }
    }

    private static bool HasAddress(Guid iface)
    {
        try
        {
            var id = iface.ToString("B");
            var nic = NetworkInterface.GetAllNetworkInterfaces()
                .FirstOrDefault(n => string.Equals(n.Id, id, StringComparison.OrdinalIgnoreCase));
            return nic != null && nic.OperationalStatus == OperationalStatus.Up &&
                   nic.GetIPProperties().UnicastAddresses.Any(a =>
                       a.Address.AddressFamily == AddressFamily.InterNetwork &&
                       !a.Address.ToString().StartsWith("169.254."));
        }
        catch
        {
            return false;
        }
    }

    private static string ProfileXml(string ssid, string password)
    {
        string name = SecurityElement.Escape(ssid) ?? ssid;
        string key = SecurityElement.Escape(password) ?? password;
        return $@"<?xml version=""1.0""?>
<WLANProfile xmlns=""http://www.microsoft.com/networking/WLAN/profile/v1"">
  <name>{name}</name>
  <SSIDConfig><SSID><name>{name}</name></SSID></SSIDConfig>
  <connectionType>ESS</connectionType>
  <connectionMode>manual</connectionMode>
  <MSM><security>
    <authEncryption><authentication>WPA2PSK</authentication><encryption>AES</encryption><useOneX>false</useOneX></authEncryption>
    <sharedKey><keyType>passPhrase</keyType><protected>false</protected><keyMaterial>{key}</keyMaterial></sharedKey>
  </security></MSM>
</WLANProfile>";
    }

    /// Minimal wrapper over wlanapi.dll.
    private sealed class Wlan : IDisposable
    {
        private readonly IntPtr _handle;

        public Wlan()
        {
            int err = WlanOpenHandle(2, IntPtr.Zero, out _, out _handle);
            if (err != 0) throw new InvalidOperationException($"WlanOpenHandle {err}");
        }

        public void Dispose() => WlanCloseHandle(_handle, IntPtr.Zero);

        /// The Wi-Fi adapter - the connected one if there are several.
        public Guid? WifiInterface()
        {
            if (WlanEnumInterfaces(_handle, IntPtr.Zero, out var list) != 0) return null;
            try
            {
                int count = Marshal.ReadInt32(list, 0);
                Guid? first = null;
                for (int i = 0; i < count; i++)
                {
                    var item = list + 8 + i * 532; // WLAN_INTERFACE_INFO = GUID(16) + WCHAR[256](512) + state(4)
                    var guid = Marshal.PtrToStructure<Guid>(item);
                    int state = Marshal.ReadInt32(item, 528);
                    first ??= guid;
                    if (state == 1) return guid; // wlan_interface_state_connected
                }
                return first;
            }
            finally
            {
                WlanFreeMemory(list);
            }
        }

        public string? CurrentProfile(Guid iface)
        {
            // wlan_intf_opcode_current_connection = 7 -> WLAN_CONNECTION_ATTRIBUTES, profile name at offset 8
            if (WlanQueryInterface(_handle, ref iface, 7, IntPtr.Zero, out _, out var data, out _) != 0) return null;
            try { return Marshal.PtrToStringUni(data + 8); }
            finally { WlanFreeMemory(data); }
        }

        public void SetProfile(Guid iface, string xml)
        {
            int err = WlanSetProfile(_handle, ref iface, 0, xml, null, true, IntPtr.Zero, out var reason);
            if (err != 0) err = WlanSetProfile(_handle, ref iface, 2 /* per-user */, xml, null, true, IntPtr.Zero, out reason);
            if (err != 0) throw new InvalidOperationException($"WlanSetProfile {err} (reason {reason})");
        }

        public void Connect(Guid iface, string profile)
        {
            var p = new WlanConnectionParameters
            {
                Mode = 0, // wlan_connection_mode_profile
                Profile = profile,
                Ssid = IntPtr.Zero,
                BssidList = IntPtr.Zero,
                BssType = 1, // dot11_BSS_type_infrastructure
                Flags = 0
            };
            int err = WlanConnect(_handle, ref iface, ref p, IntPtr.Zero);
            if (err != 0) throw new InvalidOperationException($"WlanConnect {err}");
        }

        public void DeleteProfile(Guid iface, string profile) => WlanDeleteProfile(_handle, ref iface, profile, IntPtr.Zero);

        [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
        private struct WlanConnectionParameters
        {
            public int Mode;
            [MarshalAs(UnmanagedType.LPWStr)] public string Profile;
            public IntPtr Ssid;
            public IntPtr BssidList;
            public int BssType;
            public uint Flags;
        }

        [DllImport("wlanapi.dll")]
        private static extern int WlanOpenHandle(uint clientVersion, IntPtr reserved, out uint negotiatedVersion, out IntPtr clientHandle);

        [DllImport("wlanapi.dll")]
        private static extern int WlanCloseHandle(IntPtr clientHandle, IntPtr reserved);

        [DllImport("wlanapi.dll")]
        private static extern int WlanEnumInterfaces(IntPtr clientHandle, IntPtr reserved, out IntPtr interfaceList);

        [DllImport("wlanapi.dll")]
        private static extern void WlanFreeMemory(IntPtr memory);

        [DllImport("wlanapi.dll")]
        private static extern int WlanQueryInterface(IntPtr clientHandle, ref Guid interfaceGuid, int opCode,
            IntPtr reserved, out int dataSize, out IntPtr data, out int opcodeValueType);

        [DllImport("wlanapi.dll", CharSet = CharSet.Unicode)]
        private static extern int WlanSetProfile(IntPtr clientHandle, ref Guid interfaceGuid, uint flags, string profileXml,
            string? allUserProfileSecurity, bool overwrite, IntPtr reserved, out uint reasonCode);

        [DllImport("wlanapi.dll", CharSet = CharSet.Unicode)]
        private static extern int WlanDeleteProfile(IntPtr clientHandle, ref Guid interfaceGuid, string profileName, IntPtr reserved);

        [DllImport("wlanapi.dll")]
        private static extern int WlanConnect(IntPtr clientHandle, ref Guid interfaceGuid,
            ref WlanConnectionParameters parameters, IntPtr reserved);
    }
}
