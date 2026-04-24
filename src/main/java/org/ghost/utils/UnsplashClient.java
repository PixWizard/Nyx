package org.ghost.utils;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import javax.imageio.ImageIO;

public class UnsplashClient {

    // IMPORTANT: Replace this with your actual Unsplash Access Key
    // Register at https://unsplash.com/developers
    private static final String ACCESS_KEY = "owFzNh9-AULGPkRR81p2AMD3Mrxxx5nfX3MRdQQz814";

    private static final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /**
     * Fetches a random image from Unsplash with dimensions sufficient for the required bits.
     * 
     * @param requiredPixels Number of pixels needed (each pixel stores 3 bits).
     * @param savePath Local path to save the downloaded image.
     * @return The loaded BufferedImage.
     * @throws Exception if there's any network or parsing error.
     */
    public static BufferedImage fetchCarrierImage(long requiredPixels, String savePath) throws Exception {
        if (ACCESS_KEY.equals("YOUR_UNSPLASH_ACCESS_KEY")) {
            throw new IllegalStateException("Unsplash Access Key not set! Please update ACCESS_KEY in UnsplashClient.java");
        }

        // 1. Calculate dimensions. We add a 20% margin for safety.
        // total pixels needed = requiredPixels / 3 bits per pixel
        long targetPixels = (long) (requiredPixels * 1.2 / 3);
        int side = (int) Math.ceil(Math.sqrt(targetPixels));
        
        // Ensure minimum "HD" dimensions (1920x1080)
        int width = Math.max(side, 1920);
        int height = Math.max(side, 1080);

        System.out.println("Searching Unsplash for an HD image (min " + width + "x" + height + ")...");

        // 2. Get random photo metadata from Unsplash
        String apiUrl = "https://api.unsplash.com/photos/random?client_id=" + ACCESS_KEY + "&query=nature,landscape";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .header("Accept-Version", "v1")
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("Unsplash API Error: " + response.statusCode() + " - " + response.body());
        }

        // 3. Simple manual JSON parsing for "urls":{"raw":"..."}
        String body = response.body();
        String rawUrl = extractField(body, "\"raw\":\"", "\"");

        if (rawUrl == null) {
            throw new RuntimeException("Could not find image URL in Unsplash response.");
        }

        // Unescape common Unicode sequences (JSON often escapes '&' as \u0026)
        rawUrl = rawUrl.replace("\\u0026", "&");

        // 4. Append dimensions to the raw URL
        // fit=crop ensures we get exactly the dimensions we asked for
        String imageUrlWithDims = rawUrl + "&w=" + width + "&h=" + height + "&fit=crop&q=80&fm=png";
        
        System.out.println("Downloading carrier image...");
        HttpRequest imgRequest = HttpRequest.newBuilder()
                .uri(URI.create(imageUrlWithDims))
                .build();

        HttpResponse<InputStream> imgResponse = client.send(imgRequest, HttpResponse.BodyHandlers.ofInputStream());

        if (imgResponse.statusCode() != 200) {
            throw new RuntimeException("Image download failed with status " + imgResponse.statusCode());
        }

        // 5. Save and return the image
        Path localPath = Paths.get(savePath);
        Files.deleteIfExists(localPath);
        
        try (InputStream is = imgResponse.body()) {
            BufferedImage image = ImageIO.read(is);
            if (image == null) {
                throw new RuntimeException("Failed to decode downloaded image.");
            }
            
            // Save it as a png locally so the user has the 'original' carrier
            ImageIO.write(image, "png", localPath.toFile());
            
            // CONVERSION: MUST convert to ARGB to avoid lossy color-space conversion 
            // when modifying LSBs via setRGB.
            BufferedImage argbImage = ImageManager.convertToARGB(image);
            
            System.out.println("Carrier image successfully fetched and saved to " + savePath);
            return argbImage;
        }
    }

    private static String extractField(String json, String startMarker, String endMarker) {
        int start = json.indexOf(startMarker);
        if (start == -1) return null;
        start += startMarker.length();
        int end = json.indexOf(endMarker, start);
        if (end == -1) return null;
        return json.substring(start, end);
    }
}