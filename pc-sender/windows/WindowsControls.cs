using System;
using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;

internal static class WindowsControls
{
    private static readonly Guid EventContext = Guid.Empty;

    [STAThread]
    private static void Main()
    {
        using (var input = new StreamReader(Console.OpenStandardInput(), Encoding.ASCII))
        using (var output = new StreamWriter(Console.OpenStandardOutput(), new UTF8Encoding(false)) { AutoFlush = true })
        {
            string line;
            while ((line = input.ReadLine()) != null)
            {
                try { output.WriteLine("OK " + Execute(line)); }
                catch (Exception error) { output.WriteLine("ERROR " + error.Message.Split('\r', '\n')[0]); }
            }
        }
    }

    private static string Execute(string line)
    {
        switch (line)
        {
            case "state":
                string audio;
                try { audio = Audio("get", 0); }
                catch (Exception) { audio = "NA:0"; }
                return "STAT:" + audio + ":" + Battery();
            case "mute": return "VOL:" + Audio("mute", 0);
            case "up": return "VOL:" + Audio("step", 5);
            case "down": return "VOL:" + Audio("step", -5);
            case "lock":
                if (!LockWorkStation()) throw new InvalidOperationException("Windows no pudo bloquear esta sesión.");
                return "Windows aceptó el bloqueo.";
            case "sleep":
                EnableShutdownPrivilege();
                if (!SetSuspendState(false, false, false))
                    throw new InvalidOperationException("Windows no pudo suspender el equipo.");
                return "Windows aceptó la suspensión.";
            case "shutdown": return SchedulePower(false);
            case "restart": return SchedulePower(true);
        }

        int percent;
        if (line.StartsWith("set ", StringComparison.Ordinal) &&
            Int32.TryParse(line.Substring(4), NumberStyles.None, CultureInfo.InvariantCulture, out percent) &&
            percent >= 0 && percent <= 100)
            return "VOL:" + Audio("set", percent);
        throw new InvalidOperationException("Orden de Windows desconocida.");
    }

    private static string SchedulePower(bool restart)
    {
        var start = new ProcessStartInfo(Path.Combine(Environment.SystemDirectory, "shutdown.exe"),
            restart ? "/r /t 0" : "/s /t 0");
        start.UseShellExecute = false;
        start.CreateNoWindow = true;
        using (var process = Process.Start(start))
        {
            if (!process.WaitForExit(3500) || process.ExitCode != 0)
                throw new InvalidOperationException("Windows rechazó la solicitud de energía.");
        }
        return restart ? "Windows aceptó el reinicio." : "Windows aceptó el apagado.";
    }

    private static string Audio(string action, int value)
    {
        IMMDeviceEnumerator enumerator = null;
        IMMDevice device = null;
        object activated = null;
        try
        {
            enumerator = (IMMDeviceEnumerator)new MMDeviceEnumerator();
            Check(enumerator.GetDefaultAudioEndpoint(0, 1, out device));
            Guid iid = typeof(IAudioEndpointVolume).GUID;
            Check(device.Activate(ref iid, 23, IntPtr.Zero, out activated));
            var endpoint = (IAudioEndpointVolume)activated;
            float level;
            bool muted;
            Check(endpoint.GetMasterVolumeLevelScalar(out level));
            Check(endpoint.GetMute(out muted));
            Guid context = EventContext;
            if (action == "mute") Check(endpoint.SetMute(!muted, ref context));
            if (action == "step" || action == "set")
            {
                float requested = action == "step" ? level + value / 100f : value / 100f;
                Check(endpoint.SetMasterVolumeLevelScalar(Math.Max(0f, Math.Min(1f, requested)), ref context));
            }
            Check(endpoint.GetMasterVolumeLevelScalar(out level));
            Check(endpoint.GetMute(out muted));
            return ((int)Math.Round(100 * level)).ToString(CultureInfo.InvariantCulture) + (muted ? ":1" : ":0");
        }
        catch (COMException error)
        {
            throw new InvalidOperationException("No se pudo acceder al audio predeterminado de Windows (0x" +
                error.ErrorCode.ToString("X8", CultureInfo.InvariantCulture) + ").");
        }
        finally
        {
            if (activated != null) Marshal.ReleaseComObject(activated);
            if (device != null) Marshal.ReleaseComObject(device);
            if (enumerator != null) Marshal.ReleaseComObject(enumerator);
        }
    }

    private static void Check(int hr) { if (hr < 0) Marshal.ThrowExceptionForHR(hr); }

    private static string Battery()
    {
        PowerStatus status;
        if (!GetSystemPowerStatus(out status) || status.Percent > 100 || (status.BatteryFlags & 128) != 0)
            return "NA:0";
        return status.Percent.ToString(CultureInfo.InvariantCulture) + ((status.BatteryFlags & 8) != 0 ? ":1" : ":0");
    }

    private static void EnableShutdownPrivilege()
    {
        IntPtr token;
        if (!OpenProcessToken(Process.GetCurrentProcess().Handle, 0x28, out token)) return;
        try
        {
            TokenPrivileges privileges = new TokenPrivileges { Count = 1, Attributes = 2 };
            if (LookupPrivilegeValue(null, "SeShutdownPrivilege", out privileges.Luid))
                AdjustTokenPrivileges(token, false, ref privileges, 0, IntPtr.Zero, IntPtr.Zero);
        }
        finally { CloseHandle(token); }
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct PowerStatus { public byte Ac, BatteryFlags, Percent, Reserved; public uint Lifetime, FullLifetime; }
    [StructLayout(LayoutKind.Sequential)]
    private struct TokenPrivileges { public uint Count; public PrivilegeId Luid; public uint Attributes; }
    [StructLayout(LayoutKind.Sequential)]
    private struct PrivilegeId { public uint Low; public int High; }

    [DllImport("kernel32.dll")] private static extern bool GetSystemPowerStatus(out PowerStatus status);
    [DllImport("user32.dll")] private static extern bool LockWorkStation();
    [DllImport("powrprof.dll")] [return: MarshalAs(UnmanagedType.U1)]
    private static extern bool SetSuspendState([MarshalAs(UnmanagedType.U1)] bool hibernate,
        [MarshalAs(UnmanagedType.U1)] bool force, [MarshalAs(UnmanagedType.U1)] bool disableWakeEvent);
    [DllImport("advapi32.dll", SetLastError = true)] private static extern bool OpenProcessToken(IntPtr process, uint access, out IntPtr token);
    [DllImport("advapi32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern bool LookupPrivilegeValue(string system, string name, out PrivilegeId luid);
    [DllImport("advapi32.dll", SetLastError = true)] private static extern bool AdjustTokenPrivileges(IntPtr token,
        bool disable, ref TokenPrivileges state, uint length, IntPtr previous, IntPtr returnLength);
    [DllImport("kernel32.dll")] private static extern bool CloseHandle(IntPtr handle);

    [ComImport, Guid("BCDE0395-E52F-467C-8E3D-C4579291692E")]
    private class MMDeviceEnumerator { }

    [ComImport, InterfaceType(ComInterfaceType.InterfaceIsIUnknown), Guid("A95664D2-9614-4F35-A746-DE8DB63617E6")]
    private interface IMMDeviceEnumerator
    {
        [PreserveSig] int EnumAudioEndpoints(int flow, uint mask, out IntPtr devices);
        [PreserveSig] int GetDefaultAudioEndpoint(int flow, int role, out IMMDevice device);
    }

    [ComImport, InterfaceType(ComInterfaceType.InterfaceIsIUnknown), Guid("D666063F-1587-4E43-81F1-B948E807363F")]
    private interface IMMDevice
    {
        [PreserveSig] int Activate(ref Guid iid, uint context, IntPtr parameters, [MarshalAs(UnmanagedType.IUnknown)] out object value);
    }

    [ComImport, InterfaceType(ComInterfaceType.InterfaceIsIUnknown), Guid("5CDF2C82-841E-4546-9722-0CF74078229A")]
    private interface IAudioEndpointVolume
    {
        [PreserveSig] int RegisterControlChangeNotify(IntPtr callback);
        [PreserveSig] int UnregisterControlChangeNotify(IntPtr callback);
        [PreserveSig] int GetChannelCount(out uint channels);
        [PreserveSig] int SetMasterVolumeLevel(float level, ref Guid context);
        [PreserveSig] int SetMasterVolumeLevelScalar(float level, ref Guid context);
        [PreserveSig] int GetMasterVolumeLevel(out float level);
        [PreserveSig] int GetMasterVolumeLevelScalar(out float level);
        [PreserveSig] int SetChannelVolumeLevel(uint channel, float level, ref Guid context);
        [PreserveSig] int SetChannelVolumeLevelScalar(uint channel, float level, ref Guid context);
        [PreserveSig] int GetChannelVolumeLevel(uint channel, out float level);
        [PreserveSig] int GetChannelVolumeLevelScalar(uint channel, out float level);
        [PreserveSig] int SetMute([MarshalAs(UnmanagedType.Bool)] bool mute, ref Guid context);
        [PreserveSig] int GetMute([MarshalAs(UnmanagedType.Bool)] out bool mute);
    }
}
