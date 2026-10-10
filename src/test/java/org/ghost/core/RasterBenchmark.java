package org.ghost.core;

import org.ghost.crypto.NyxKeyDerivation;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Benchmark harness comparing direct raster access vs reference getRGB/setRGB copy path.
 * Measures wall time, allocated memory per operation, peak heap, and isolates Argon2id cost.
 * Not run by default 'mvn test' due to class naming convention.
 */
public class RasterBenchmark {

    static class ThreadAllocatedBytesTracker {
        private final Object mxBean;
        private final Method getThreadAllocatedBytesMethod;
        private final long threadId;

        ThreadAllocatedBytesTracker() {
            Object bean = null;
            Method method = null;
            long tid = Thread.currentThread().threadId();
            try {
                Class<?> mfClass = Class.forName("java.lang.management.ManagementFactory");
                Object threadBean = mfClass.getMethod("getThreadMXBean").invoke(null);
                Class<?> sunThreadBeanClass = Class.forName("com.sun.management.ThreadMXBean");
                if (sunThreadBeanClass.isInstance(threadBean)) {
                    Method isSupported = sunThreadBeanClass.getMethod("isThreadAllocatedMemorySupported");
                    if ((Boolean) isSupported.invoke(threadBean)) {
                        Method isEnabled = sunThreadBeanClass.getMethod("isThreadAllocatedMemoryEnabled");
                        if (!(Boolean) isEnabled.invoke(threadBean)) {
                            sunThreadBeanClass.getMethod("setThreadAllocatedMemoryEnabled", boolean.class).invoke(threadBean, true);
                        }
                        bean = threadBean;
                        method = sunThreadBeanClass.getMethod("getThreadAllocatedBytes", long.class);
                    }
                }
            } catch (Throwable ignored) {
            }
            this.mxBean = bean;
            this.getThreadAllocatedBytesMethod = method;
            this.threadId = tid;
        }

        boolean isAvailable() {
            return mxBean != null && getThreadAllocatedBytesMethod != null;
        }

        long getCurrentThreadAllocatedBytes() {
            if (isAvailable()) {
                try {
                    return (Long) getThreadAllocatedBytesMethod.invoke(mxBean, threadId);
                } catch (Throwable ignored) {
                }
            }
            return 0;
        }
    }

    private static List<String> getJvmInputArguments() {
        try {
            Class<?> mfClass = Class.forName("java.lang.management.ManagementFactory");
            Object runtimeBean = mfClass.getMethod("getRuntimeMXBean").invoke(null);
            @SuppressWarnings("unchecked")
            List<String> args = (List<String>) runtimeBean.getClass().getMethod("getInputArguments").invoke(runtimeBean);
            return args != null ? args : List.of();
        } catch (Throwable ignored) {
            return List.of();
        }
    }

    private static BufferedImage createPatternCarrier(int width, int height, long seed) {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        int[] pixels = RasterAccess.directPixels(img);
        Random rng = new Random(seed);
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = (0xFF << 24) | rng.nextInt(0x01000000);
        }
        return img;
    }

    @Test
    void runBenchmarkTest() {
        runBenchmarks();
    }

    public static void main(String[] args) {
        runBenchmarks();
    }

    public static void runBenchmarks() {
        System.out.println("=========================================================================================");
        System.out.println("                    NYX STEGANOGRAPHY - RASTER ACCESS BENCHMARKS                         ");
        System.out.println("=========================================================================================");

        // Environment details
        List<String> jvmFlags = getJvmInputArguments();
        System.out.println("JVM Version: " + System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")");
        System.out.println("JVM VM:      " + System.getProperty("java.vm.name") + " " + System.getProperty("java.vm.version"));
        System.out.println("OS:          " + System.getProperty("os.name") + " " + System.getProperty("os.version") + " (" + System.getProperty("os.arch") + ")");
        System.out.println("CPUs:        " + Runtime.getRuntime().availableProcessors());
        System.out.println("Max Heap:    " + (Runtime.getRuntime().maxMemory() / (1024 * 1024)) + " MB");
        System.out.println("JVM Flags:   " + String.join(" ", jvmFlags));
        System.out.println("-----------------------------------------------------------------------------------------\n");

        ThreadAllocatedBytesTracker tracker = new ThreadAllocatedBytesTracker();
        System.out.println("ThreadAllocatedBytes Supported: " + tracker.isAvailable());

        SteganoEngine directEngine = new SteganoEngine();
        ReferenceSteganoEngine refEngine = new ReferenceSteganoEngine();

        char[] password = "BenchmarkSecretKey2026!".toCharArray();
        byte[] salt = new byte[16];
        byte[] nonce = new byte[12];
        new Random(777L).nextBytes(salt);
        new Random(888L).nextBytes(nonce);

        // Measure isolated Argon2id key derivation time
        System.out.println(">>> Isolating Argon2id KDF Baseline Cost (65536 KiB memory, 3 iterations, 1 parallelism)...");
        List<Double> argonTimes = new ArrayList<>();
        long argonAllocStart = tracker.getCurrentThreadAllocatedBytes();
        for (int i = 0; i < 5; i++) {
            long t0 = System.nanoTime();
            try (NyxKeyDerivation.DerivedKeys keys = NyxKeyDerivation.deriveKeys(password.clone(), salt, 65536, 3, 1)) {
                long t1 = System.nanoTime();
                argonTimes.add((t1 - t0) / 1_000_000.0);
            }
        }
        long argonAllocDelta = tracker.isAvailable() ? (tracker.getCurrentThreadAllocatedBytes() - argonAllocStart) / 5 : 0;
        Collections.sort(argonTimes);
        double argonMedianMs = argonTimes.get(argonTimes.size() / 2);
        System.out.printf("Argon2id Median Wall Time: %.2f ms (Spread: %.2f - %.2f ms), Alloc: ~%.2f MB%n%n",
                argonMedianMs, argonTimes.get(0), argonTimes.get(argonTimes.size() - 1), argonAllocDelta / (1024.0 * 1024.0));

        // Resolutions to benchmark
        record BenchmarkTarget(String name, int width, int height, int payloadBytes) {}
        List<BenchmarkTarget> targets = List.of(
                new BenchmarkTarget("1080p (1920x1080)", 1920, 1080, 500 * 1024), // 500 KB fits depth 1 capacity (759 KB)
                new BenchmarkTarget("4K    (3840x2160)", 3840, 2160, 2 * 1024 * 1024), // 2 MB fits depth 1 capacity (2.96 MB)
                new BenchmarkTarget("8K    (7680x4320)", 7680, 4320, 2 * 1024 * 1024)  // 2 MB fits depth 1 capacity (12.4 MB)
        );

        int warmups = 2;
        int measuredRuns = 5;

        for (BenchmarkTarget target : targets) {
            System.out.printf("=========================================================================================%n");
            System.out.printf("BENCHMARK: %s | Resolution: %dx%d (%d pixels) | Payload: %.2f MB%n",
                    target.name(), target.width(), target.height(), target.width() * target.height(), target.payloadBytes() / (1024.0 * 1024.0));
            System.out.printf("=========================================================================================%n");

            byte[] payload = new byte[target.payloadBytes()];
            new Random(1234L + target.width()).nextBytes(payload);

            // Pre-allocate base carrier
            BufferedImage baseImg = createPatternCarrier(target.width(), target.height(), 42L);
            int[] basePixels = RasterAccess.directPixels(baseImg);

            // Benchmark cloak (encode)
            System.out.println("\n--- ENCODE (CLOAK) ---");
            BenchmarkResult refCloak = measureOperation("Reference (getRGB/setRGB)", warmups, measuredRuns, tracker, () -> {
                BufferedImage carrier = new BufferedImage(target.width(), target.height(), BufferedImage.TYPE_INT_ARGB);
                System.arraycopy(basePixels, 0, RasterAccess.directPixels(carrier), 0, basePixels.length);
                refEngine.cloakWithFixedCrypto(carrier, payload, "bin", password.clone(), false, false, salt, nonce);
                return carrier;
            });

            BenchmarkResult directCloak = measureOperation("Direct Raster Access", warmups, measuredRuns, tracker, () -> {
                BufferedImage carrier = new BufferedImage(target.width(), target.height(), BufferedImage.TYPE_INT_ARGB);
                System.arraycopy(basePixels, 0, RasterAccess.directPixels(carrier), 0, basePixels.length);
                directEngine.cloakWithFixedCrypto(carrier, payload, "bin", password.clone(), false, false, salt, nonce);
                return carrier;
            });

            printComparison(refCloak, directCloak, argonMedianMs);

            // Pre-create stego image for decode benchmark
            BufferedImage stegoDirect = new BufferedImage(target.width(), target.height(), BufferedImage.TYPE_INT_ARGB);
            System.arraycopy(basePixels, 0, RasterAccess.directPixels(stegoDirect), 0, basePixels.length);
            directEngine.cloakWithFixedCrypto(stegoDirect, payload, "bin", password.clone(), false, false, salt, nonce);

            BufferedImage stegoRef = new BufferedImage(target.width(), target.height(), BufferedImage.TYPE_INT_ARGB);
            System.arraycopy(basePixels, 0, RasterAccess.directPixels(stegoRef), 0, basePixels.length);
            refEngine.cloakWithFixedCrypto(stegoRef, payload, "bin", password.clone(), false, false, salt, nonce);

            // Benchmark reveal (decode)
            System.out.println("\n--- DECODE (REVEAL) ---");
            BenchmarkResult refReveal = measureOperation("Reference (getRGB)", warmups, measuredRuns, tracker, () -> {
                return refEngine.reveal(stegoRef, password.clone());
            });

            BenchmarkResult directReveal = measureOperation("Direct Raster Access", warmups, measuredRuns, tracker, () -> {
                return directEngine.reveal(stegoDirect, password.clone());
            });

            printComparison(refReveal, directReveal, argonMedianMs);
            System.out.println();
        }
    }

    private static void printComparison(BenchmarkResult ref, BenchmarkResult direct, double argonMedianMs) {
        double refRasterOnlyMs = Math.max(0, ref.medianTimeMs - argonMedianMs);
        double directRasterOnlyMs = Math.max(0, direct.medianTimeMs - argonMedianMs);
        double rasterSpeedup = (directRasterOnlyMs > 0.001) ? (refRasterOnlyMs / directRasterOnlyMs) : 1.0;
        double savedAllocMb = (ref.allocatedBytes - direct.allocatedBytes) / (1024.0 * 1024.0);

        System.out.printf("%-26s | Median: %7.2f ms (Spread: %6.2f - %6.2f ms) | Alloc: %8.2f MB | Peak: %7.1f MB%n",
                ref.name, ref.medianTimeMs, ref.minTimeMs, ref.maxTimeMs, ref.allocatedBytes / (1024.0 * 1024.0), ref.peakHeapMb);
        System.out.printf("%-26s | Median: %7.2f ms (Spread: %6.2f - %6.2f ms) | Alloc: %8.2f MB | Peak: %7.1f MB%n",
                direct.name, direct.medianTimeMs, direct.minTimeMs, direct.maxTimeMs, direct.allocatedBytes / (1024.0 * 1024.0), direct.peakHeapMb);
        System.out.printf("--> Raster-only time: Ref=%.2f ms vs Direct=%.2f ms (Speedup: %.2fx) | Memory Saved: %.2f MB%n",
                refRasterOnlyMs, directRasterOnlyMs, rasterSpeedup, savedAllocMb);
    }

    private record BenchmarkResult(String name, double medianTimeMs, double minTimeMs, double maxTimeMs, long allocatedBytes, double peakHeapMb) {}

    private static BenchmarkResult measureOperation(String name, int warmups, int runs, ThreadAllocatedBytesTracker tracker, java.util.function.Supplier<Object> op) {
        // Warmup
        for (int i = 0; i < warmups; i++) {
            op.get();
        }

        List<Double> times = new ArrayList<>();
        long totalAlloc = 0;
        double peakHeapMb = 0;

        for (int i = 0; i < runs; i++) {
            System.gc();
            long allocStart = tracker.getCurrentThreadAllocatedBytes();
            long t0 = System.nanoTime();
            Object res = op.get();
            long t1 = System.nanoTime();
            long allocEnd = tracker.getCurrentThreadAllocatedBytes();

            times.add((t1 - t0) / 1_000_000.0);
            totalAlloc += Math.max(0, allocEnd - allocStart);

            Runtime rt = Runtime.getRuntime();
            double heapUsed = (rt.totalMemory() - rt.freeMemory()) / (1024.0 * 1024.0);
            if (heapUsed > peakHeapMb) peakHeapMb = heapUsed;
        }

        Collections.sort(times);
        double median = times.get(times.size() / 2);
        double min = times.get(0);
        double max = times.get(times.size() - 1);
        long avgAlloc = totalAlloc / runs;

        return new BenchmarkResult(name, median, min, max, avgAlloc, peakHeapMb);
    }
}
