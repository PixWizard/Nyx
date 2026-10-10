package org.ghost.core;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class SteganoEngineCharacterizationTest {

    @Test
    void testRoundTripSmallPayloadThroughPngEncodeDecode() throws IOException {
        SteganoProtocol engine = new SteganoEngine();

        // Synthetic carrier image with 400 pixels capacity
        int width = 20;
        int height = 20;
        BufferedImage carrier = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = carrier.createGraphics();
        g.setColor(new Color(120, 150, 180, 255));
        g.fillRect(0, 0, width, height);
        g.dispose();

        byte[] payload = "Hello, Nyx!".getBytes(StandardCharsets.UTF_8);

        engine.cloak(carrier, payload);

        // PNG encode/decode round trip via ImageIO
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        boolean written = ImageIO.write(carrier, "png", baos);
        if (!written) {
            throw new IllegalStateException("ImageIO failed to find PNG writer");
        }
        byte[] pngBytes = baos.toByteArray();

        BufferedImage decodedImage = ImageIO.read(new ByteArrayInputStream(pngBytes));
        assertNotNull(decodedImage, "Decoded image must not be null");

        byte[] revealed = engine.reveal(decodedImage);
        assertArrayEquals(payload, revealed, "Revealed payload must match original payload byte-for-byte");
    }

    @Test
    void testRoundTripZeroBytePayload() throws IOException {
        SteganoProtocol engine = new SteganoEngine();

        // Synthetic carrier with sufficient capacity for 50-byte (400-bit / 134-pixel) envelope header
        int width = 20;
        int height = 20; // 400 pixels > 134 header pixels
        BufferedImage carrier = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = carrier.createGraphics();
        g.setColor(new Color(100, 110, 120, 255));
        g.fillRect(0, 0, width, height);
        g.dispose();

        byte[] payload = new byte[0];

        engine.cloak(carrier, payload);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(carrier, "png", baos);
        byte[] pngBytes = baos.toByteArray();

        BufferedImage decodedImage = ImageIO.read(new ByteArrayInputStream(pngBytes));
        assertNotNull(decodedImage);

        byte[] revealed = engine.reveal(decodedImage);

        // Verifying behavior: 0-byte payload allocates new byte[0] and returns it at the end of pixel traversal
        assertNotNull(revealed, "Revealed array should not be null for 0-byte payload");
        assertArrayEquals(payload, revealed, "Revealed array should be an empty byte array matching original");
    }
}
