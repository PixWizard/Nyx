package org.ghost.eval;

import org.ghost.core.DepthProfile;
import org.ghost.core.RasterAccess;
import org.ghost.core.SequentialLayout;
import org.ghost.core.SobelEdgeDetector;
import org.ghost.core.SteganoEngine;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.Random;

/**
 * Test-scoped steganography evaluation tool.
 * Provides:
 *  1. PSNR (8-bit per channel on R, G, B, MAX = 255).
 *  2. Westfeld-Pfitzmann Chi-Square pairs-of-values test over progressive image prefixes.
 *  3. Depth histogram (counts and percentages at depth 0/1/2/3).
 *  4. Mean absolute change (MAC) on pixels where Sobel G < T1 vs G >= T2 (Adaptive vs Constant).
 *
 * This tool does not assert cryptographic undetectability.
 */
public class SteganographyEvaluationTool {

    public record PsnrResult(double r, double g, double b, double average) {
        @Override
        public String toString() {
            String rStr = Double.isInfinite(r) ? "Inf" : String.format("%.2f dB", r);
            String gStr = Double.isInfinite(g) ? "Inf" : String.format("%.2f dB", g);
            String bStr = Double.isInfinite(b) ? "Inf" : String.format("%.2f dB", b);
            String avgStr = Double.isInfinite(average) ? "Inf" : String.format("%.2f dB", average);
            return String.format("R=%s, G=%s, B=%s (Avg=%s)", rStr, gStr, bStr, avgStr);
        }
    }

    public record ChiSquareResult(double chiSquare, int df, double pValue) {
        @Override
        public String toString() {
            return String.format("chi2=%.2f (df=%d), p-value=%.6f", chiSquare, df, pValue);
        }
    }

    public record DepthHistogram(int d0, int d1, int d2, int d3, int totalPool) {
        @Override
        public String toString() {
            return String.format("Depth 0: %d (%.1f%%) | Depth 1: %d (%.1f%%) | Depth 2: %d (%.1f%%) | Depth 3: %d (%.1f%%)",
                    d0, (100.0 * d0) / totalPool,
                    d1, (100.0 * d1) / totalPool,
                    d2, (100.0 * d2) / totalPool,
                    d3, (100.0 * d3) / totalPool);
        }
    }

    public record MacResult(double macFlat, double macEdge, int countFlat, int countEdge) {}

    public static DepthHistogram computeDepthHistogram(BufferedImage carrier) {
        int width = carrier.getWidth();
        int height = carrier.getHeight();
        int[] pixels = RasterAccess.directPixels(carrier);
        if (pixels == null) pixels = RasterAccess.directPixels(RasterAccess.toCanonicalImage(carrier));

        int[] mask = SobelEdgeDetector.computeMask(pixels, width, height, DepthProfile.DEFAULT);
        int totalPixels = width * height;
        int d0 = 0, d1 = 0, d2 = 0, d3 = 0;
        int totalPool = totalPixels - SequentialLayout.HEADER_PIXELS;

        for (int p = SequentialLayout.HEADER_PIXELS; p < totalPixels; p++) {
            switch (mask[p]) {
                case 0 -> d0++;
                case 1 -> d1++;
                case 2 -> d2++;
                case 3 -> d3++;
            }
        }
        return new DepthHistogram(d0, d1, d2, d3, totalPool);
    }

    public static MacResult computeMac(BufferedImage cover, BufferedImage stego) {
        int width = cover.getWidth();
        int height = cover.getHeight();
        int[] cPixels = RasterAccess.directPixels(cover);
        if (cPixels == null) cPixels = RasterAccess.directPixels(RasterAccess.toCanonicalImage(cover));
        int[] sPixels = RasterAccess.directPixels(stego);
        if (sPixels == null) sPixels = RasterAccess.directPixels(RasterAccess.toCanonicalImage(stego));

        DepthProfile profile = DepthProfile.DEFAULT;
        int[] mask = SobelEdgeDetector.computeMask(cPixels, width, height, profile);
        int totalPixels = width * height;

        long sumChangeFlat = 0;
        int countFlat = 0;
        long sumChangeEdge = 0;
        int countEdge = 0;

        for (int p = SequentialLayout.HEADER_PIXELS; p < totalPixels; p++) {
            int d = mask[p];
            int c = cPixels[p];
            int s = sPixels[p];

            int dR = Math.abs(((c >> 16) & 0xFF) - ((s >> 16) & 0xFF));
            int dG = Math.abs(((c >> 8) & 0xFF) - ((s >> 8) & 0xFF));
            int dB = Math.abs((c & 0xFF) - (s & 0xFF));
            int totalDiff = dR + dG + dB; // sum of absolute differences across R, G, B

            if (d == 0) { // G < T1 (16) -> smooth/flat
                sumChangeFlat += totalDiff;
                countFlat++;
            } else if (d >= 2) { // G >= T2 (48) -> edge/texture
                sumChangeEdge += totalDiff;
                countEdge++;
            }
        }

        double macFlat = (countFlat > 0) ? (double) sumChangeFlat / (countFlat * 3.0) : 0.0;
        double macEdge = (countEdge > 0) ? (double) sumChangeEdge / (countEdge * 3.0) : 0.0;
        return new MacResult(macFlat, macEdge, countFlat, countEdge);
    }

    public static PsnrResult computePsnr(BufferedImage cover, BufferedImage stego) {
        int width = cover.getWidth();
        int height = cover.getHeight();
        int[] cPixels = RasterAccess.directPixels(cover);
        if (cPixels == null) cPixels = RasterAccess.directPixels(RasterAccess.toCanonicalImage(cover));
        int[] sPixels = RasterAccess.directPixels(stego);
        if (sPixels == null) sPixels = RasterAccess.directPixels(RasterAccess.toCanonicalImage(stego));

        long sumSqR = 0, sumSqG = 0, sumSqB = 0;
        int totalPixels = width * height;

        for (int i = 0; i < totalPixels; i++) {
            int c = cPixels[i];
            int s = sPixels[i];

            int dR = ((c >> 16) & 0xFF) - ((s >> 16) & 0xFF);
            sumSqR += (long) dR * dR;

            int dG = ((c >> 8) & 0xFF) - ((s >> 8) & 0xFF);
            sumSqG += (long) dG * dG;

            int dB = (c & 0xFF) - (s & 0xFF);
            sumSqB += (long) dB * dB;
        }

        double mseR = (double) sumSqR / totalPixels;
        double mseG = (double) sumSqG / totalPixels;
        double mseB = (double) sumSqB / totalPixels;

        double maxSq = 255.0 * 255.0;
        double psnrR = (mseR == 0.0) ? Double.POSITIVE_INFINITY : 10.0 * Math.log10(maxSq / mseR);
        double psnrG = (mseG == 0.0) ? Double.POSITIVE_INFINITY : 10.0 * Math.log10(maxSq / mseG);
        double psnrB = (mseB == 0.0) ? Double.POSITIVE_INFINITY : 10.0 * Math.log10(maxSq / mseB);

        double avgPsnr = (Double.isInfinite(psnrR) && Double.isInfinite(psnrG) && Double.isInfinite(psnrB))
                ? Double.POSITIVE_INFINITY
                : (psnrR + psnrG + psnrB) / 3.0;

        return new PsnrResult(psnrR, psnrG, psnrB, avgPsnr);
    }

    public static ChiSquareResult computeChiSquarePoV(BufferedImage image, int prefixLength, int channelShift) {
        int[] pixels = RasterAccess.directPixels(image);
        if (pixels == null) pixels = RasterAccess.directPixels(RasterAccess.toCanonicalImage(image));

        int n = Math.min(prefixLength, pixels.length);
        int[] counts = new int[256];
        for (int i = 0; i < n; i++) {
            int val = (pixels[i] >> channelShift) & 0xFF;
            counts[val]++;
        }

        double chiSquare = 0.0;
        int validPairs = 0;

        for (int k = 0; k < 128; k++) {
            int c0 = counts[2 * k];
            int c1 = counts[2 * k + 1];
            int sum = c0 + c1;
            if (sum > 0) {
                validPairs++;
                double diff = c0 - c1;
                chiSquare += (diff * diff) / (2.0 * sum);
            }
        }

        int df = Math.max(1, validPairs - 1);
        double pValue = chiSquareSurvival(df, chiSquare);
        return new ChiSquareResult(chiSquare, df, pValue);
    }

    public static double chiSquareSurvival(int df, double x) {
        if (x <= 0.0) return 1.0;
        double a = df / 2.0;
        double z = x / 2.0;
        return gammaSurvival(a, z);
    }

    private static double gammaSurvival(double a, double z) {
        if (z < 0.0 || a <= 0.0) return 0.0;
        if (z == 0.0) return 1.0;

        if (z < a + 1.0) {
            double p = gammaLowerSeries(a, z);
            return Math.max(0.0, Math.min(1.0, 1.0 - p));
        } else {
            return Math.max(0.0, Math.min(1.0, gammaUpperContinuedFraction(a, z)));
        }
    }

    private static double gammaLowerSeries(double a, double z) {
        double sum = 1.0 / a;
        double term = 1.0 / a;
        for (int n = 1; n < 200; n++) {
            term *= z / (a + n);
            sum += term;
            if (Math.abs(term) < Math.abs(sum) * 1e-15) {
                break;
            }
        }
        double logPrefactor = a * Math.log(z) - z - logGamma(a);
        return Math.exp(logPrefactor) * sum;
    }

    private static double gammaUpperContinuedFraction(double a, double z) {
        final double TINY = 1e-30;
        double b = z + 1.0 - a;
        double c = 1.0 / TINY;
        double d = 1.0 / b;
        double h = d;

        for (int i = 1; i < 200; i++) {
            double an = -i * (i - a);
            b += 2.0;
            d = an * d + b;
            if (Math.abs(d) < TINY) d = TINY;
            c = b + an / c;
            if (Math.abs(c) < TINY) c = TINY;
            d = 1.0 / d;
            double delta = d * c;
            h *= delta;
            if (Math.abs(delta - 1.0) < 1e-15) {
                break;
            }
        }
        double logPrefactor = a * Math.log(z) - z - logGamma(a);
        return Math.exp(logPrefactor) * h;
    }

    public static double logGamma(double z) {
        double[] p = {
                676.5203681218851,
                -1259.1392167224028,
                771.32342877765313,
                -176.61502916214059,
                12.507343278686905,
                -0.13857109526572012,
                9.9843695780195716e-6,
                1.5056327351493116e-7
        };
        double x = z - 1.0;
        double base = x + 7.5;
        double a = 0.99999999999980993;
        for (int i = 0; i < p.length; i++) {
            a += p[i] / (x + i + 1);
        }
        return 0.5 * Math.log(2.0 * Math.PI) + (x + 0.5) * Math.log(base) - base + Math.log(a);
    }

    public static void main(String[] args) {
        runEvaluationSuite();
    }

    public static void runEvaluationSuite() {
        System.out.println("=========================================================================================");
        System.out.println("                    NYX STEGANOGRAPHY - COMPREHENSIVE EVALUATION                         ");
        System.out.println("=========================================================================================");

        int width = 512;
        int height = 512;

        record NamedCarrier(String name, BufferedImage image) {}
        NamedCarrier[] carriers = {
                new NamedCarrier("Smooth Continuous-Tone Carrier", createContinuousCarrier(width, height)),
                new NamedCarrier("Textured Noise-Plus-Edges Carrier", createTexturedCarrier(width, height))
        };

        SteganoEngine engine = new SteganoEngine();
        char[] password = "EvaluationSecurePass2026!".toCharArray();
        byte[] payload10k = new byte[10 * 1024];
        new Random(42L).nextBytes(payload10k);

        // Depth histograms
        System.out.println("\n--- DEPTH HISTOGRAMS (SOBEL POOL PIXELS) ---");
        for (NamedCarrier c : carriers) {
            DepthHistogram hist = computeDepthHistogram(c.image());
            System.out.printf("%-36s | Total Pool: %d pixels%n   %s%n", c.name(), hist.totalPool(), hist);
        }

        // Mean Absolute Change (MAC) on flat vs edge regions
        System.out.println("\n--- MEAN ABSOLUTE CHANGE (MAC) PER CHANNEL: FLAT (G < T1) vs EDGE (G >= T2) ---");
        System.out.printf("%-32s | %-16s | %-14s | %-14s%n", "Carrier", "Mode", "MAC (G < 16)", "MAC (G >= 48)");
        System.out.println("-----------------------------------------------------------------------------------------");

        for (NamedCarrier c : carriers) {
            // Constant Depth 1
            BufferedImage stegoConst = cloneImage(c.image());
            stegoConst = engine.cloak(stegoConst, payload10k, "bin", password.clone(), true, false);
            MacResult macConst = computeMac(c.image(), stegoConst);

            // Adaptive Depth
            BufferedImage stegoAdapt = cloneImage(c.image());
            stegoAdapt = engine.cloak(stegoAdapt, payload10k, "bin", password.clone(), true, true);
            MacResult macAdapt = computeMac(c.image(), stegoAdapt);

            System.out.printf("%-32s | %-16s | %10.4f     | %10.4f    %n",
                    c.name(), "Constant Depth 1", macConst.macFlat(), macConst.macEdge());
            System.out.printf("%-32s | %-16s | %10.4f     | %10.4f    %n",
                    c.name(), "Sobel Adaptive  ", macAdapt.macFlat(), macAdapt.macEdge());
            System.out.println("-----------------------------------------------------------------------------------------");
        }

        // PSNR evaluation across layouts on textured carrier
        System.out.println("\n--- PSNR EVALUATION (8-BIT PER CHANNEL) ON TEXTURED CARRIER ---");
        System.out.printf("%-32s | %-12s | %-12s | %-12s | %-12s%n", "Layout Configuration", "PSNR Red", "PSNR Green", "PSNR Blue", "PSNR Avg");
        System.out.println("-----------------------------------------------------------------------------------------");

        BufferedImage textCover = carriers[1].image();
        record LayoutConfig(String name, boolean scatter, boolean adaptive) {}
        LayoutConfig[] configs = {
                new LayoutConfig("Sequential + Constant Depth 1", false, false),
                new LayoutConfig("Scattered  + Constant Depth 1", true, false),
                new LayoutConfig("Sequential + Sobel Adaptive   ", false, true),
                new LayoutConfig("Scattered  + Sobel Adaptive   ", true, true)
        };

        BufferedImage stegoSeqText = null;
        BufferedImage stegoScatText = null;

        for (LayoutConfig cfg : configs) {
            BufferedImage stego = cloneImage(textCover);
            stego = engine.cloak(stego, payload10k, "bin", password.clone(), cfg.scatter(), cfg.adaptive());

            if (!cfg.scatter() && !cfg.adaptive()) stegoSeqText = stego;
            if (cfg.scatter() && !cfg.adaptive()) stegoScatText = stego;

            PsnrResult psnr = computePsnr(textCover, stego);
            System.out.printf("%-32s | %10.2f dB | %10.2f dB | %10.2f dB | %10.2f dB%n",
                    cfg.name(), psnr.r(), psnr.g(), psnr.b(), psnr.average());
        }

        // Westfeld-Pfitzmann progressive Chi-square test on textured carrier
        System.out.println("\n--- WESTFELD-PFITZMANN CHI-SQUARE TEST (PROGRESSIVE PREFIXES ON TEXTURED CARRIER) ---");
        System.out.println("Channel: Blue (shift=0) | Comparing Sequential vs Scattered (Payload = 10 KB)");
        System.out.println("Cover pixels total: " + (width * height) + " pixels");
        System.out.println("-----------------------------------------------------------------------------------------");
        System.out.printf("%-10s | %-12s | %-24s | %-24s%n",
                "Prefix %", "Pixels", "Sequential (chi2 / p)", "Scattered (chi2 / p)");
        System.out.println("-----------------------------------------------------------------------------------------");

        int totalPixels = width * height;
        for (int pct = 10; pct <= 100; pct += 10) {
            int prefixLen = (totalPixels * pct) / 100;
            ChiSquareResult seqRes = computeChiSquarePoV(stegoSeqText, prefixLen, 0);
            ChiSquareResult scatRes = computeChiSquarePoV(stegoScatText, prefixLen, 0);

            System.out.printf("%8d%%  | %12d | chi2=%8.2f (p=%.6f) | chi2=%8.2f (p=%.6f)%n",
                    pct, prefixLen, seqRes.chiSquare(), seqRes.pValue(), scatRes.chiSquare(), scatRes.pValue());
        }
        System.out.println("=========================================================================================\n");
    }

    private static BufferedImage cloneImage(BufferedImage src) {
        BufferedImage copy = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
        int[] srcPixels = RasterAccess.directPixels(src);
        int[] dstPixels = RasterAccess.directPixels(copy);
        System.arraycopy(srcPixels, 0, dstPixels, 0, srcPixels.length);
        return copy;
    }

    public static BufferedImage createContinuousCarrier(int width, int height) {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        int[] pixels = RasterAccess.directPixels(img);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int r = (int) (128 + 120 * Math.sin(x * 0.02));
                int g = (int) (128 + 120 * Math.cos(y * 0.02));
                int b = (int) (128 + 100 * Math.sin((x + y) * 0.015));
                pixels[y * width + x] = (0xFF << 24) | ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF);
            }
        }
        return img;
    }

    public static BufferedImage createTexturedCarrier(int width, int height) {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = img.createGraphics();

        // Base gradient
        for (int y = 0; y < height; y++) {
            int v = (y * 200) / height;
            g2.setColor(new Color(v, v, v));
            g2.drawLine(0, y, width, y);
        }

        // High-contrast geometric edges
        g2.setColor(new Color(240, 240, 240));
        g2.fillRect(50, 50, 150, 150);
        g2.setColor(new Color(20, 20, 20));
        g2.fillOval(250, 100, 180, 180);
        g2.setColor(new Color(200, 50, 50));
        g2.fillRect(100, 300, 300, 80);

        g2.dispose();

        // High-frequency pseudo-random texture overlay
        int[] pixels = RasterAccess.directPixels(img);
        Random rng = new Random(777L);
        for (int i = 0; i < pixels.length; i++) {
            int px = pixels[i];
            int r = (px >> 16) & 0xFF;
            int g = (px >> 8) & 0xFF;
            int b = px & 0xFF;

            int noise = rng.nextInt(31) - 15; // +/- 15 noise
            r = Math.max(0, Math.min(255, r + noise));
            g = Math.max(0, Math.min(255, g + noise));
            b = Math.max(0, Math.min(255, b + noise));

            pixels[i] = (0xFF << 24) | (r << 16) | (g << 8) | b;
        }

        return img;
    }
}
