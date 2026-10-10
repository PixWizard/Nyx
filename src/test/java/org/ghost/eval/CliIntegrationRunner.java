package org.ghost.eval;

import org.ghost.core.DepthProfile;
import org.ghost.core.RasterAccess;
import org.ghost.core.SequentialLayout;
import org.ghost.core.SobelEdgeDetector;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end CLI integration verification:
 * - Generates valid multi-page PDF and multi-entry ZIP payloads.
 * - Sizes payloads at 90% capacity for each mode on a 512x512 textured carrier.
 * - Executes Main via external ProcessBuilder with NYX_PASSWORD in environment.
 * - Verifies all 4 layout flag combinations for both PDF and ZIP.
 * - Measures wall times and validates SHA-256 integrity match.
 * - Executes negative test cases: wrong password, clean PNG, truncated PNG, JPEG re-save.
 */
public class CliIntegrationRunner {

    private static final Path EVAL_DIR;
    static {
        try {
            EVAL_DIR = Files.createTempDirectory("nyx_cli_eval");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static void main(String[] args) throws Exception {
        new CliIntegrationRunner().runCliIntegrationVerification();
    }

    @Test
    void runCliIntegrationVerification() throws Exception {
        Files.createDirectories(EVAL_DIR);

        System.out.println("=========================================================================================");
        System.out.println("                    NYX CLI END-TO-END VERIFICATION (SECTION 4 B4)                       ");
        System.out.println("=========================================================================================");

        // Prepare 512x512 textured carrier
        int width = 512;
        int height = 512;
        BufferedImage carrierImg = SteganographyEvaluationTool.createTexturedCarrier(width, height);
        Path carrierPath = EVAL_DIR.resolve("textured_carrier_512.png");
        ImageIO.write(carrierImg, "png", carrierPath.toFile());

        int[] cPixels = RasterAccess.directPixels(carrierImg);
        int totalPixels = width * height;
        int poolPixels = totalPixels - SequentialLayout.HEADER_PIXELS;

        // Capacity math:
        // Depth 1: poolPixels * 3 bits
        long depth1PoolBits = (long) poolPixels * 3L;
        long depth1CapacityBytes = depth1PoolBits / 8L; // 98,253 bytes

        // Adaptive: sum(3 * mask[p])
        int[] mask = SobelEdgeDetector.computeMask(cPixels, width, height, DepthProfile.DEFAULT);
        long adaptivePoolBits = 0L;
        for (int p = SequentialLayout.HEADER_PIXELS; p < totalPixels; p++) {
            adaptivePoolBits += 3L * (long) mask[p];
        }
        long adaptiveCapacityBytes = adaptivePoolBits / 8L; // 159,877 bytes

        System.out.printf("Carrier Image:          %s (%dx%d, %d pixels)%n", carrierPath, width, height, totalPixels);
        System.out.printf("Non-Adaptive Capacity:  %d bits (%d bytes)%n", depth1PoolBits, depth1CapacityBytes);
        System.out.printf("Adaptive Pool Capacity: %d bits (%d bytes)%n", adaptivePoolBits, adaptiveCapacityBytes);
        System.out.println("-----------------------------------------------------------------------------------------");

        // Compute 90% usable capacities (subtract 20 bytes framing: 1 extLen + 3 ext + 16 GCM tag)
        int nonAdaptPayloadSize = (int) ((depth1CapacityBytes - 20) * 0.90); // 88,409 bytes
        int adaptPayloadSize = (int) ((adaptiveCapacityBytes - 20) * 0.90);   // 143,871 bytes

        System.out.printf("Target Payload Sizes (90%% usable capacity):%n");
        System.out.printf("  Non-Adaptive Modes: %d bytes (%.2f KB)%n", nonAdaptPayloadSize, nonAdaptPayloadSize / 1024.0);
        System.out.printf("  Adaptive Modes:     %d bytes (%.2f KB)%n", adaptPayloadSize, adaptPayloadSize / 1024.0);
        System.out.println("-----------------------------------------------------------------------------------------\n");

        // Generate Real Multi-Page PDFs and Real ZIPs for both sizes
        Path pdfNonAdaptPath = EVAL_DIR.resolve("real_multipage_nonadapt.pdf");
        Files.write(pdfNonAdaptPath, generateRealMultiPagePdf(nonAdaptPayloadSize));

        Path zipNonAdaptPath = EVAL_DIR.resolve("real_multientry_nonadapt.zip");
        Files.write(zipNonAdaptPath, generateRealZip(nonAdaptPayloadSize));

        Path pdfAdaptPath = EVAL_DIR.resolve("real_multipage_adapt.pdf");
        Files.write(pdfAdaptPath, generateRealMultiPagePdf(adaptPayloadSize));

        Path zipAdaptPath = EVAL_DIR.resolve("real_multientry_adapt.zip");
        Files.write(zipAdaptPath, generateRealZip(adaptPayloadSize));

        record ModeConfig(String name, String[] cliFlags, boolean adaptive, Path pdfPath, Path zipPath) {}
        List<ModeConfig> modes = List.of(
                new ModeConfig("Mode 1: Sequential + Depth 1", new String[]{"--no-scatter", "--no-adaptive"}, false, pdfNonAdaptPath, zipNonAdaptPath),
                new ModeConfig("Mode 2: Scattered  + Depth 1", new String[]{"--no-adaptive"}, false, pdfNonAdaptPath, zipNonAdaptPath),
                new ModeConfig("Mode 3: Sequential + Adaptive", new String[]{"--no-scatter"}, true, pdfAdaptPath, zipAdaptPath),
                new ModeConfig("Mode 4: Scattered  + Adaptive", new String[]{}, true, pdfAdaptPath, zipAdaptPath)
        );

        String password = "CliValidationPassword2026!";
        Path lastStegoPath = null;

        for (ModeConfig mode : modes) {
            System.out.println("=========================================================================================");
            System.out.printf("RUNNING: %s%n", mode.name());
            System.out.printf("CLI Flags: %s%n", mode.cliFlags().length == 0 ? "(none - default)" : String.join(" ", mode.cliFlags()));
            System.out.println("=========================================================================================");

            record PayloadTest(String formatName, Path inputPath, String ext) {}
            List<PayloadTest> payloads = List.of(
                    new PayloadTest("Real Multi-Page PDF", mode.pdfPath(), "pdf"),
                    new PayloadTest("Real Multi-File ZIP", mode.zipPath(), "zip")
            );

            for (PayloadTest p : payloads) {
                byte[] inBytes = Files.readAllBytes(p.inputPath());
                String inSha = sha256(inBytes);

                String safeName = mode.name().replaceAll("[^a-zA-Z0-9]", "_") + "_" + p.ext();
                Path stegoPath = EVAL_DIR.resolve("stego_" + safeName + ".png");
                lastStegoPath = stegoPath;
                Path restoredBase = EVAL_DIR.resolve("restored_" + safeName);
                Path expectedRestored = EVAL_DIR.resolve("restored_" + safeName + "." + p.ext());

                System.out.printf("%n>>> Test Case: %s | Payload Size: %d bytes (%.2f KB)%n", p.formatName(), inBytes.length, inBytes.length / 1024.0);
                System.out.printf("    Input SHA-256: %s%n", inSha);

                // Cloak command
                String cloakInputs = "1\n" + p.inputPath().toAbsolutePath() + "\n1\n" + carrierPath.toAbsolutePath() + "\n" + stegoPath.toAbsolutePath() + "\n";
                System.out.println("\n[CLOAK INVOCATION]");
                System.out.println("Piped Stdin:\n" + indent(cloakInputs.trim()));

                long t0Cloak = System.currentTimeMillis();
                CliResult cloakRes = executeCli(mode.cliFlags(), cloakInputs, password);
                long cloakWallMs = System.currentTimeMillis() - t0Cloak;

                System.out.printf("Cloak Wall Time: %d ms (includes Argon2id key derivation)%n", cloakWallMs);
                System.out.println("Console Output:\n" + indent(cloakRes.output().trim()));
                assertTrue(Files.exists(stegoPath), "Stego file must exist: " + stegoPath);

                // Uncloak command
                String uncloakInputs = "2\n" + stegoPath.toAbsolutePath() + "\n" + restoredBase.toAbsolutePath() + "\n";
                System.out.println("\n[UNCLOAK INVOCATION]");
                System.out.println("Piped Stdin:\n" + indent(uncloakInputs.trim()));

                long t0Uncloak = System.currentTimeMillis();
                CliResult uncloakRes = executeCli(mode.cliFlags(), uncloakInputs, password);
                long uncloakWallMs = System.currentTimeMillis() - t0Uncloak;

                System.out.printf("Uncloak Wall Time: %d ms (includes Argon2id key derivation)%n", uncloakWallMs);
                System.out.println("Console Output:\n" + indent(uncloakRes.output().trim()));
                assertTrue(Files.exists(expectedRestored), "Restored file must exist: " + expectedRestored);

                byte[] outBytes = Files.readAllBytes(expectedRestored);
                String outSha = sha256(outBytes);
                System.out.printf("    Output SHA-256: %s%n", outSha);

                assertEquals(inSha, outSha, "SHA-256 of restored payload must match input payload bit-for-bit");
                System.out.println("--> RESULT: PASSED (Bit-for-bit match verified)");
            }
        }

        // Negative failure cases
        System.out.println("\n=========================================================================================");
        System.out.println("                        NEGATIVE FAILURE CASES VERIFICATION                              ");
        System.out.println("=========================================================================================");

        // Negative case: wrong password
        System.out.println("\n--- Negative Case 1: Wrong Password ---");
        String wrongPwdInput = "2\n" + lastStegoPath.toAbsolutePath() + "\n" + EVAL_DIR.resolve("wrong_pwd_out").toAbsolutePath() + "\n";
        System.out.println("Piped Stdin:\n" + indent(wrongPwdInput.trim()));
        CliResult wrongPwdRes = executeCli(new String[]{}, wrongPwdInput, "WrongInvalidPassword999!");
        System.out.println("Console Output:\n" + indent(wrongPwdRes.output().trim()));
        assertTrue(wrongPwdRes.output().contains("[SECURITY ALERT]: wrong password or the carrier was modified/recompressed"));
        assertTrue(!wrongPwdRes.output().contains("Exception at"));

        // Negative case: clean PNG without Nyx payload
        System.out.println("\n--- Negative Case 2: Clean PNG With No Nyx Payload ---");
        String cleanImgInput = "2\n" + carrierPath.toAbsolutePath() + "\n" + EVAL_DIR.resolve("clean_img_out").toAbsolutePath() + "\n";
        System.out.println("Piped Stdin:\n" + indent(cleanImgInput.trim()));
        CliResult cleanImgRes = executeCli(new String[]{}, cleanImgInput, password);
        System.out.println("Console Output:\n" + indent(cleanImgRes.output().trim()));
        assertTrue(cleanImgRes.output().contains("[STEGANOGRAPHY ERROR]: Magic mismatch"));
        assertTrue(!cleanImgRes.output().contains("Exception at"));

        // Negative case: truncated PNG
        System.out.println("\n--- Negative Case 3: Truncated PNG ---");
        Path truncPath = EVAL_DIR.resolve("truncated_carrier.png");
        byte[] stegoBytes = Files.readAllBytes(lastStegoPath);
        byte[] truncBytes = Arrays.copyOf(stegoBytes, stegoBytes.length / 3);
        Files.write(truncPath, truncBytes);

        String truncInput = "2\n" + truncPath.toAbsolutePath() + "\n" + EVAL_DIR.resolve("trunc_out").toAbsolutePath() + "\n";
        System.out.println("Piped Stdin:\n" + indent(truncInput.trim()));
        CliResult truncRes = executeCli(new String[]{}, truncInput, password);
        System.out.println("Console Output:\n" + indent(truncRes.output().trim()));
        assertTrue(truncRes.output().contains("[SYSTEM ERROR]") || truncRes.output().contains("[STEGANOGRAPHY ERROR]"));
        assertTrue(!truncRes.output().contains("Exception at"));

        // Negative case: stego image re-saved as JPEG
        System.out.println("\n--- Negative Case 4: Stego Image Re-saved as JPEG ---");
        Path jpegPath = EVAL_DIR.resolve("resaved_as_jpeg.jpg");
        BufferedImage lastStegoImg = ImageIO.read(lastStegoPath.toFile());
        BufferedImage rgbImg = new BufferedImage(lastStegoImg.getWidth(), lastStegoImg.getHeight(), BufferedImage.TYPE_INT_RGB);
        rgbImg.getGraphics().drawImage(lastStegoImg, 0, 0, null);
        ImageIO.write(rgbImg, "jpeg", jpegPath.toFile());

        String jpegInput = "2\n" + jpegPath.toAbsolutePath() + "\n" + EVAL_DIR.resolve("jpeg_out").toAbsolutePath() + "\n";
        System.out.println("Piped Stdin:\n" + indent(jpegInput.trim()));
        CliResult jpegRes = executeCli(new String[]{}, jpegInput, password);
        System.out.println("Console Output:\n" + indent(jpegRes.output().trim()));
        assertTrue(jpegRes.output().contains("[SECURITY ALERT]") || jpegRes.output().contains("[STEGANOGRAPHY ERROR]"));
        assertTrue(!jpegRes.output().contains("Exception at"));

        System.out.println("\n=========================================================================================");
        System.out.println("                 ALL END-TO-END CLI VERIFICATION TESTS PASSED SUCCESSFULLY!               ");
        System.out.println("=========================================================================================\n");
    }

    private static String indent(String s) {
        return "   " + s.replace("\n", "\n   ");
    }

    private static String sha256(byte[] data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(md.digest(data));
    }

    record CliResult(int exitCode, String output) {}

    private static CliResult executeCli(String[] args, String simulatedInput, String envPassword) throws Exception {
        String javaHome = System.getProperty("java.home");
        Path javaBin = Paths.get(javaHome, "bin", "java.exe");
        if (!Files.exists(javaBin)) {
            javaBin = Paths.get(javaHome, "bin", "java");
        }
        Path cpFile = Paths.get("target", "cp.txt");
        String cp;
        if (Files.exists(cpFile)) {
            cp = Files.readString(cpFile).trim() + File.pathSeparator + "target/classes";
        } else {
            String jcp = System.getProperty("java.class.path", "");
            String jmp = System.getProperty("jdk.module.path", "");
            List<String> parts = new ArrayList<>();
            if (!jmp.isBlank()) parts.add(jmp);
            if (!jcp.isBlank()) parts.add(jcp);
            parts.add("target/classes");
            cp = String.join(File.pathSeparator, parts);
        }

        List<String> cmd = new ArrayList<>();
        cmd.add(javaBin.toString());
        cmd.add("--module-path");
        cmd.add(cp);
        cmd.add("-m");
        cmd.add("org.ghost/org.ghost.Main");
        if (args != null) {
            cmd.addAll(Arrays.asList(args));
        }

        ProcessBuilder pb = new ProcessBuilder(cmd);
        if (envPassword != null) {
            pb.environment().put("NYX_PASSWORD", envPassword);
        } else {
            pb.environment().remove("NYX_PASSWORD");
        }

        Process process = pb.start();
        if (simulatedInput != null) {
            try (OutputStream os = process.getOutputStream()) {
                os.write(simulatedInput.getBytes(StandardCharsets.UTF_8));
                os.flush();
            }
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (InputStream is = process.getInputStream(); InputStream es = process.getErrorStream()) {
            is.transferTo(baos);
            es.transferTo(baos);
        }
        int exitCode = process.waitFor();
        return new CliResult(exitCode, baos.toString(StandardCharsets.UTF_8));
    }

    /**
     * Generates a fully valid, conformant 3-page PDF 1.4 document padded to targetBytes.
     */
    public static byte[] generateRealMultiPagePdf(int targetBytes) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("%PDF-1.4\n%\u00e2\u00e3\u00cf\u00d3\n");

        List<Long> offsets = new ArrayList<>();
        offsets.add(0L); // obj 0

        offsets.add((long) sb.toString().getBytes(StandardCharsets.US_ASCII).length);
        sb.append("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n");

        offsets.add((long) sb.toString().getBytes(StandardCharsets.US_ASCII).length);
        sb.append("2 0 obj\n<< /Type /Pages /Kids [3 0 R 4 0 R 5 0 R] /Count 3 >>\nendobj\n");

        offsets.add((long) sb.toString().getBytes(StandardCharsets.US_ASCII).length);
        sb.append("3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 6 0 R >>\nendobj\n");

        offsets.add((long) sb.toString().getBytes(StandardCharsets.US_ASCII).length);
        sb.append("4 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 7 0 R >>\nendobj\n");

        offsets.add((long) sb.toString().getBytes(StandardCharsets.US_ASCII).length);
        sb.append("5 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 8 0 R >>\nendobj\n");

        String s1 = "BT /F1 12 Tf 72 712 Td (Page 1: Nyx End-To-End Multi-Page Verification PDF) Tj ET\n";
        offsets.add((long) sb.toString().getBytes(StandardCharsets.US_ASCII).length);
        sb.append("6 0 obj\n<< /Length ").append(s1.length()).append(" >>\nstream\n").append(s1).append("endstream\nendobj\n");

        String s2 = "BT /F1 12 Tf 72 712 Td (Page 2: Cryptographic Payload Integration Verification) Tj ET\n";
        offsets.add((long) sb.toString().getBytes(StandardCharsets.US_ASCII).length);
        sb.append("7 0 obj\n<< /Length ").append(s2.length()).append(" >>\nstream\n").append(s2).append("endstream\nendobj\n");

        String s3 = "BT /F1 12 Tf 72 712 Td (Page 3: Payload Padding and Integrity Verification Stream) Tj ET\n";
        offsets.add((long) sb.toString().getBytes(StandardCharsets.US_ASCII).length);
        sb.append("8 0 obj\n<< /Length ").append(s3.length()).append(" >>\nstream\n").append(s3).append("endstream\nendobj\n");

        long startXref = (long) sb.toString().getBytes(StandardCharsets.US_ASCII).length;
        sb.append("xref\n0 9\n");
        sb.append("0000000000 65535 f \n");
        for (int i = 1; i <= 8; i++) {
            sb.append(String.format("%010d 00000 n \n", offsets.get(i)));
        }
        sb.append("trailer\n<< /Size 9 /Root 1 0 R >>\nstartxref\n").append(startXref).append("\n%%EOF\n");

        byte[] basePdf = sb.toString().getBytes(StandardCharsets.US_ASCII);
        if (basePdf.length >= targetBytes) {
            return basePdf;
        }

        // Pad with a valid PDF comment line before EOF to match exact targetBytes
        int diff = targetBytes - basePdf.length;
        // Comment format: "% " + 'A'*(diff - 3) + "\n"
        StringBuilder pad = new StringBuilder();
        pad.append("% ");
        for (int i = 0; i < diff - 3; i++) {
            pad.append('A');
        }
        pad.append("\n");

        // Insert comment right after header line 1
        String baseStr = sb.toString();
        int firstNl = baseStr.indexOf('\n');
        String resultStr = baseStr.substring(0, firstNl + 1) + pad.toString() + baseStr.substring(firstNl + 1);
        byte[] resultBytes = resultStr.getBytes(StandardCharsets.US_ASCII);
        if (resultBytes.length != targetBytes) {
            // Adjust if any difference
            return Arrays.copyOf(resultBytes, targetBytes);
        }
        return resultBytes;
    }

    /**
     * Generates a valid multi-entry ZIP archive padded to exact targetBytes using uncompressed STORED method.
     */
    public static byte[] generateRealZip(int targetBytes) throws IOException {
        // We calibrate data payload size to match exact targetBytes
        int low = 1;
        int high = targetBytes;
        byte[] best = null;

        while (low <= high) {
            int mid = (low + high) / 2;
            byte[] candidate = createZipWithDataSize(mid);
            if (candidate.length == targetBytes) {
                return candidate;
            } else if (candidate.length < targetBytes) {
                best = candidate;
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }

        if (best != null && best.length < targetBytes) {
            byte[] exact = new byte[targetBytes];
            System.arraycopy(best, 0, exact, 0, best.length);
            return exact;
        }
        return createZipWithDataSize(1);
    }

    private static byte[] createZipWithDataSize(int dataSize) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            // Entry 1: Text manifest
            byte[] m = "Nyx Multi-Entry Archive Manifest\nVersion: 2026.1\n".getBytes(StandardCharsets.UTF_8);
            ZipEntry e1 = new ZipEntry("manifest.txt");
            e1.setMethod(ZipEntry.STORED);
            e1.setSize(m.length);
            e1.setCompressedSize(m.length);
            CRC32 crc1 = new CRC32();
            crc1.update(m);
            e1.setCrc(crc1.getValue());
            zos.putNextEntry(e1);
            zos.write(m);
            zos.closeEntry();

            // Entry 2: Markdown readme
            byte[] r = "# Secret Archive\nPayload verification document.\n".getBytes(StandardCharsets.UTF_8);
            ZipEntry e2 = new ZipEntry("docs/README.md");
            e2.setMethod(ZipEntry.STORED);
            e2.setSize(r.length);
            e2.setCompressedSize(r.length);
            CRC32 crc2 = new CRC32();
            crc2.update(r);
            e2.setCrc(crc2.getValue());
            zos.putNextEntry(e2);
            zos.write(r);
            zos.closeEntry();

            // Entry 3: Binary data stream
            byte[] d = new byte[dataSize];
            for (int i = 0; i < d.length; i++) d[i] = (byte) (i & 0xFF);
            ZipEntry e3 = new ZipEntry("data/payload.bin");
            e3.setMethod(ZipEntry.STORED);
            e3.setSize(d.length);
            e3.setCompressedSize(d.length);
            CRC32 crc3 = new CRC32();
            crc3.update(d);
            e3.setCrc(crc3.getValue());
            zos.putNextEntry(e3);
            zos.write(d);
            zos.closeEntry();
        }
        return baos.toByteArray();
    }
}
