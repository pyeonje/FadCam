param([string]$JdkHome = $env:JAVA_HOME)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path $PSScriptRoot -Parent
$source = [IO.File]::ReadAllText((Join-Path $repoRoot 'app/src/main/java/com/fadcam/services/RecordingService.java'))
$methodStart = $source.IndexOf('    private void attemptStartRecordingIfReady() {')
$methodEnd = $source.IndexOf('    // wait -----------', $methodStart)
if ($methodStart -lt 0 -or $methodEnd -lt 0) { throw 'Cannot extract readiness method from production source' }
$method = $source.Substring($methodStart, $methodEnd - $methodStart)
$harnessDir = Join-Path $repoRoot 'tasks/start-dispatch-harness'
New-Item -ItemType Directory -Force -Path $harnessDir | Out-Null
$prefix = @'
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
public class RecordingService {
    enum RecordingState { NONE, STARTING, IN_PROGRESS }
    static class Surface { boolean isValid() { return true; } }
    static class Handler {
        final ConcurrentLinkedQueue<Runnable> queue = new ConcurrentLinkedQueue<>();
        boolean post(Runnable r) { queue.add(r); return true; }
        void removeCallbacks(Runnable r) { }
    }
    static class FLog { static void d(String t, String m) { } static void e(String t, String m, Exception e) { throw new RuntimeException(e); } }
    String TAG = "test";
    boolean isStopping, pendingStartRecording, waitForPreviewBeforeStart;
    RecordingState recordingState = RecordingState.STARTING;
    Object cameraDevice;
    Surface previewSurface;
    Runnable previewWaitTimeoutRunnable;
    final AtomicBoolean recordingStartDispatched = new AtomicBoolean(false);
    final Handler mainHandler = new Handler(), backgroundHandler = new Handler();
    int pipelinesStarted;
    void startRecording() { pipelinesStarted++; }
    void stopRecording() { recordingState = RecordingState.NONE; }
'@
$suffix = @'
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        RecordingService service = new RecordingService();
        service.attemptStartRecordingIfReady();
        check(service.backgroundHandler.queue.isEmpty() && service.pendingStartRecording, "camera not open must not dispatch");
        service.cameraDevice = new Object();
        service.waitForPreviewBeforeStart = true;
        service.attemptStartRecordingIfReady();
        check(!service.recordingStartDispatched.get(), "missing preview must leave dispatch claim available");
        service.previewSurface = new Surface();
        for (int i = 0; i < 5; i++) service.attemptStartRecordingIfReady();
        check(service.backgroundHandler.queue.size() == 1, "camera/surface callbacks must dispatch one pipeline");
        service.backgroundHandler.queue.remove().run();
        service.attemptStartRecordingIfReady();
        check(service.pipelinesStarted == 1 && service.backgroundHandler.queue.isEmpty(), "claim must survive pipeline creation while session still STARTING");

        RecordingService concurrent = new RecordingService();
        concurrent.cameraDevice = new Object();
        ExecutorService workers = Executors.newFixedThreadPool(8);
        for (int i = 0; i < 100; i++) workers.submit(concurrent::attemptStartRecordingIfReady);
        workers.shutdown();
        check(workers.awaitTermination(5, TimeUnit.SECONDS), "workers finished");
        check(concurrent.backgroundHandler.queue.size() == 1, "concurrent readiness callbacks dispatch once");

        service.recordingStartDispatched.set(false);
        service.isStopping = true;
        service.attemptStartRecordingIfReady();
        check(service.backgroundHandler.queue.isEmpty(), "stopping must not dispatch");
        service.isStopping = false;
        service.attemptStartRecordingIfReady();
        check(service.backgroundHandler.queue.size() == 1, "new accepted session may dispatch after resetting claim");
        System.out.println("PASS: production readiness method dispatches once for camera/surface callbacks, concurrent callbacks, and capture-session delay; next session resets correctly.");
    }
}
'@
$javaSource = Join-Path $harnessDir 'RecordingService.java'
[IO.File]::WriteAllText($javaSource, $prefix + [Environment]::NewLine + $method + [Environment]::NewLine + $suffix)
if (-not $JdkHome) { throw 'Set JAVA_HOME or pass -JdkHome' }
$jdkBin = Join-Path $JdkHome 'bin'
& (Join-Path $jdkBin 'javac.exe') '-encoding' 'UTF-8' '-d' $harnessDir $javaSource
if ($LASTEXITCODE -ne 0) { throw 'Harness compilation failed' }
& (Join-Path $jdkBin 'java.exe') '-cp' $harnessDir 'RecordingService'
if ($LASTEXITCODE -ne 0) { throw 'Production method regression verification failed' }
