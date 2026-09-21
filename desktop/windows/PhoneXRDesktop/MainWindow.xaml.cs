using Microsoft.UI.Xaml;
using System.Diagnostics;
using System.Drawing;
using System.Drawing.Imaging;
using System.Net;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using System.IO;
namespace PhoneXRDesktop;
public sealed partial class MainWindow : Window {
    [DllImport("user32.dll")] static extern int GetSystemMetrics(int index);
    CancellationTokenSource? stop; TcpListener? listener; readonly List<TcpClient> clients = [];
    public MainWindow() { InitializeComponent();
#if PHONEXR_LITE
        Title="PhoneXR Lite Share"; ProductTitle.Text="PhoneXR Lite Share";
#endif
    }
    async void ToggleStream(object sender, RoutedEventArgs e) { if (Stream.IsChecked == true) { Stream.Content="Остановить передачу"; stop=new(); await Run(stop.Token); } else Stop(); }
    void Stop() { stop?.Cancel(); listener?.Stop(); foreach(var c in clients)c.Dispose(); clients.Clear(); Stream.Content="Передавать экран"; Status.Text="Остановлено"; }
    void OpenSteamVr(object sender, RoutedEventArgs e) {
        var driver = Path.Combine(AppContext.BaseDirectory, "steamvr", "phonexr");
        var steam = Environment.GetFolderPath(Environment.SpecialFolder.ProgramFilesX86);
        var vrpathreg = Path.Combine(steam, "Steam", "steamapps", "common", "SteamVR", "bin", "win64", "vrpathreg.exe");
        if (!File.Exists(vrpathreg)) { Status.Text="Сначала установите SteamVR в Steam."; return; }
        if (!File.Exists(Path.Combine(driver, "driver.vrdrivermanifest"))) { Status.Text="Драйвер PhoneXR не найден — переустановите PhoneXR Share."; return; }
        var registration = Process.Start(new ProcessStartInfo(vrpathreg, $"adddriver \"{driver}\"") { UseShellExecute=false, CreateNoWindow=true });
        registration?.WaitForExit(5000);
        Process.Start(new ProcessStartInfo("steam://rungameid/250820") { UseShellExecute=true });
        Status.Text="Драйвер PhoneXR зарегистрирован · запускаю SteamVR…";
    }
    async Task Run(CancellationToken token) {
        listener=new(IPAddress.Any,24820); listener.Start(); Status.Text="Ожидание PhoneXR в локальной сети…";
        _=Task.Run(async()=>{ using var udp=new UdpClient(); udp.EnableBroadcast=true; var endpoint=new IPEndPoint(IPAddress.Broadcast,24819); while(!token.IsCancellationRequested) { var message=System.Text.Encoding.UTF8.GetBytes($"PHONEXR_DESKTOP_V1 24820 {Environment.MachineName}"); await udp.SendAsync(message,endpoint,token); await Task.Delay(1000,token); } },token);
        _=Task.Run(async()=>{ while(!token.IsCancellationRequested) { try { var c=await listener.AcceptTcpClientAsync(token); lock(clients)clients.Add(c); DispatcherQueue.TryEnqueue(()=>Status.Text="PhoneXR подключён · передача 30 FPS"); } catch { break; } } });
        await Task.Run(async()=>{ while(!token.IsCancellationRequested) {
#if PHONEXR_LITE
            const int delay=100; int width=Math.Min(1280,GetSystemMetrics(0)); int height=GetSystemMetrics(1)*width/GetSystemMetrics(0);
#else
            const int delay=50; int width=GetSystemMetrics(0); int height=GetSystemMetrics(1);
#endif
            int screenWidth=GetSystemMetrics(0), screenHeight=GetSystemMetrics(1); using var source=new Bitmap(screenWidth,screenHeight); using(var capture=Graphics.FromImage(source))capture.CopyFromScreen(0,0,0,0,source.Size); using var bmp=new Bitmap(source,width,height); using var ms=new MemoryStream(); bmp.Save(ms,ImageFormat.Jpeg); var jpg=ms.ToArray(); var head=new byte[]{(byte)'P',(byte)'X',(byte)'S',(byte)'1',(byte)(bmp.Width>>8),(byte)bmp.Width,(byte)(bmp.Height>>8),(byte)bmp.Height,(byte)(jpg.Length>>24),(byte)(jpg.Length>>16),(byte)(jpg.Length>>8),(byte)jpg.Length}; lock(clients) foreach(var c in clients.ToArray()) try { c.GetStream().Write(head); c.GetStream().Write(jpg); } catch { clients.Remove(c); c.Dispose(); } await Task.Delay(delay,token); } },token);
    }
}
